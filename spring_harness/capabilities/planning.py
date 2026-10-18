import inspect
from collections.abc import Awaitable, Callable
from dataclasses import replace
from pathlib import Path

from pydantic_ai import RunContext
from pydantic_ai.tools import ToolDefinition
from pydantic_ai.toolsets import AbstractToolset
from pydantic_ai_harness import Planning
from pydantic_ai_harness.planning import PlanItem, PlanStore, SqlitePlanStore

from spring_harness.core.config.settings import STATE_DIR

PLAN_DB = "plan.db"


def _db_path(workspace: Path) -> str:
    """plan.db 落在工作区的 .springharness/ 下。"""
    directory = workspace / STATE_DIR
    directory.mkdir(parents=True, exist_ok=True)
    return str(directory / PLAN_DB)

OnPlanChange = Callable[[list["PlanItem"]], "None | Awaitable[None]"]


class _ObservableStore:
    """PlanStore 委托包装：写操作完成后回调最新全量 items。"""

    def __init__(self, inner: PlanStore, on_change: OnPlanChange) -> None:
        self._inner = inner
        self._on_change = on_change

    async def _notify(self) -> None:
        result = self._on_change(await self._inner.get_items())
        if inspect.isawaitable(result):
            await result

    async def get_items(self) -> list[PlanItem]:
        return await self._inner.get_items()

    async def get_item(self, item_id: str) -> PlanItem | None:
        return await self._inner.get_item(item_id)

    async def set_items(self, items: list[PlanItem]) -> None:
        await self._inner.set_items(items)
        await self._notify()

    async def add_item(self, item: PlanItem) -> PlanItem:
        item = await self._inner.add_item(item)
        await self._notify()
        return item

    async def update_item(self, item_id: str, **kwargs) -> PlanItem | None:
        item = await self._inner.update_item(item_id, **kwargs)
        await self._notify()
        return item

    async def remove_item(self, item_id: str) -> bool:
        removed = await self._inner.remove_item(item_id)
        await self._notify()
        return removed


def _prepare_descriptions(ctx: RunContext, definitions: list[ToolDefinition]) -> list[ToolDefinition]:
    prepared = []
    for definition in definitions:
        schema = definition.parameters_json_schema
        if "PlanItem" in schema.get("$defs", {}):
            schema = {**schema, "$defs": {**schema["$defs"], "PlanItem": {
                **schema["$defs"]["PlanItem"],
                "description": "一个计划步骤，包含稳定 ID、任务内容、当前状态及进行时标签；新步骤自动生成 ID。",
            }}}
            definition = replace(definition, parameters_json_schema=schema)
        prepared.append(definition)
    return prepared


class _ProjectPlanning(Planning):
    """项目计划的交互说明。

    @author hanbing
    @since 2026-10-17
    """

    def get_toolset(self):
        toolset = super().get_toolset()
        return toolset.prepared(_prepare_descriptions) if isinstance(toolset, AbstractToolset) else toolset


def planning(session: str, on_change: OnPlanChange | None = None, *, root: Path) -> Planning:
    """加载Plan；on_change 在每次计划变更后收到最新全量 items。"""
    store: PlanStore = SqlitePlanStore(_db_path(root), session=session)
    if on_change is not None:
        store = _ObservableStore(store, on_change)
    return _ProjectPlanning(
        store=store,
        guidance=(
            "对需要多个步骤的任务，先用 write_plan 建立计划，再随实际进展更新。"
            "执行期间保持一个步骤为 in_progress，达到验收标准后立即标记 completed。"
            "遇到阻碍时保留当前状态并说明所需条件。"
            "write_plan 每次提交完整计划；局部调整可用 add_task、update_task_status、"
            "update_task_statuses 或 remove_task，操作前可用 read_plan 获取步骤 ID。"
        ),
        descriptions={
            "write_plan": (
                "创建或替换当前会话的完整任务计划。每次提交全部有序步骤及其当前状态；"
                "执行期间保持一个 in_progress 步骤。"
            ),
            "read_plan": "读取当前会话的任务计划、步骤 ID、内容和状态，用于恢复进度或局部调整。",
            "add_task": "向当前计划追加一个 pending 步骤，保留已有步骤。",
            "update_task_status": (
                "按步骤 ID 更新状态。开始执行时标记 in_progress，"
                "达到该步骤的完成标准并核实结果后标记 completed。"
            ),
            "update_task_statuses": (
                "按步骤 ID 批量更新状态，适合将已完成步骤交接到下一步。"
                "整批参数校验通过后，按提交顺序执行。"
            ),
            "remove_task": "按步骤 ID 永久移除误建或已撤销的计划步骤；完成记录通过状态更新保留。",
        }
    )


async def load_plan_items(session: str, *, root: Path) -> list[PlanItem]:
    """读某会话已持久化的计划（恢复会话时重建 PlanMessage 用）。"""
    return await SqlitePlanStore(_db_path(root), session=session).get_items()


async def delete_plan(session: str, *, root: Path) -> None:
    database = Path(_db_path(root))
    if database.exists():
        await SqlitePlanStore(str(database), session=session).set_items([])
