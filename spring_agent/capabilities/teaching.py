from __future__ import annotations

import inspect
import sqlite3
import threading
from collections.abc import Awaitable, Callable
from datetime import datetime
from enum import Enum
from pathlib import Path
from uuid import uuid4

import anyio.to_thread
import yaml
from pydantic import BaseModel, Field
from pydantic_ai.toolsets import FunctionToolset

from spring_agent.core.config.settings import STATE_DIR
from spring_agent.core.storage_notifications import pending_notifications

TEACHING_DB = "teaching.db"
TEACHING_DIR = "teaching"


class Mastery(str, Enum):
    """对话验收等级：D1 独立解题 / D2 解释机制 / D3 情境迁移。"""

    D1 = "D1"
    D2 = "D2"
    D3 = "D3"


_MASTERY_ORDER = {Mastery.D1: 1, Mastery.D2: 2, Mastery.D3: 3}


class ZoneKind(str, Enum):
    """对话三区：AI 讲解示例 / 协作讨论 / 用户独立回答。"""

    agent = "agent"
    cowork = "cowork"
    human = "human"


class UnitStatus(str, Enum):
    active = "active"
    closed = "closed"


class Objective(BaseModel):
    """学习目标：可观察、可验收的能力点。"""

    id: str = Field(description="目标 id，如 OBJ-1")
    text: str = Field(description="可观察的能力点与对话验收要求")
    mastery_required: Mastery = Field(description="要求达到的验收等级")
    mastery_achieved: Mastery | None = Field(default=None, description="已达成的验收等级")


class Zone(BaseModel):
    """对话教学的分工范围。"""

    kind: ZoneKind = Field(description="对话区域：agent 讲解示例 / cowork 协作讨论 / human 独立回答")
    scope: str = Field(description="该区域的知识主题或问题范围")
    note: str = Field(default="", description="该区域的对话活动与回答要求")
    serves: list[str] = Field(
        default_factory=list,
        description='该区域服务的 objective id 列表，使用扁平字符串数组，如 ["OBJ-1", "OBJ-2"]。',
    )


class HintEvent(BaseModel):
    """一次提示升级留痕：提示有价，逐级解锁。"""

    objective_id: str
    level: int = Field(description="提示级别 1-3：L1 指方向 / L2 给结构 / L3 给对照")
    attempt: str = Field(description="学习者在当前级的真实尝试记录（升级前提）")
    at: datetime = Field(default_factory=datetime.now)


def _new_id() -> str:
    """生成单元的随机 id（8 位十六进制）。"""
    return uuid4().hex[:8]


class TeachingUnit(BaseModel):
    """一个教学单元：spec 的结构化形态，跨会话存活。"""

    id: str = Field(default_factory=_new_id)
    slug: str = Field(description="文件系统安全标识，如 rust-ownership")
    title: str
    status: UnitStatus = UnitStatus.active
    version: int = 1
    objectives: list[Objective]
    zones: list[Zone]
    hint_max_level: int = Field(default=3, description="本单元允许的最高提示级别（1-3）")
    hints: list[HintEvent] = Field(default_factory=list)
    changelog: list[str] = Field(default_factory=list, description="验收要求与对话三区的版本变更理由")
    created_at: datetime = Field(default_factory=datetime.now)
    updated_at: datetime = Field(default_factory=datetime.now)


# 单元变更回调的形状：吃一个 TeachingUnit、返回 None 或可等待的 None 的函数
OnTeachingChange = Callable[[TeachingUnit], "None | Awaitable[None]"]


def _now() -> datetime:
    return datetime.now()


def _is_slug_char(ch: str, allow_dash: bool) -> bool:
    """判断字符是否属于 slug 的小写字母、数字或可选连字符。"""
    if "a" <= ch and ch <= "z":
        return True
    if "0" <= ch and ch <= "9":
        return True
    if allow_dash and ch == "-":
        return True
    return False


def _is_valid_slug(slug: str) -> bool:
    """slug 规则：小写字母或数字开头，其余是小写字母/数字/连字符，总长 1-50。"""
    if len(slug) == 0 or len(slug) > 50:
        return False
    if not _is_slug_char(slug[0], allow_dash=False):
        return False
    for ch in slug[1:]:
        if not _is_slug_char(ch, allow_dash=True):
            return False
    return True


def _mastery_value(mastery: Mastery | None) -> str | None:
    """枚举取字符串值，None 原样返回。"""
    if mastery is None:
        return None
    return mastery.value


def _yaml_dump(obj: dict) -> str:
    """字典转为保留中文与字段顺序的 YAML 文本。"""
    return yaml.safe_dump(obj, allow_unicode=True, sort_keys=False)


def _find_objective(unit: TeachingUnit, objective_id: str) -> Objective | None:
    """按 id 返回目标或 None。"""
    for o in unit.objectives:
        if o.id == objective_id:
            return o
    return None


def _used_hint_levels(unit: TeachingUnit, objective_id: str) -> list[int]:
    """某目标已使用过的提示级别列表，如 [1, 1, 2]。"""
    levels = []
    for h in unit.hints:
        if h.objective_id == objective_id:
            levels.append(h.level)
    return levels


def _format_hint_levels(levels: list[int]) -> str:
    """把 [1, 1, 2] 格式化成 "L1×2, L2×1"。"""
    counts = {}
    for level in levels:
        counts[level] = counts.get(level, 0) + 1
    parts = []
    for level in sorted(counts):
        parts.append(f"L{level}×{counts[level]}")
    return ", ".join(parts)


class TeachingStore:
    """按工作区保存、支持跨会话恢复的 SQLite 教学单元存储。"""

    def __init__(self, workspace: Path | str, *, on_change: OnTeachingChange | None = None) -> None:
        self._data_dir = Path(workspace) / STATE_DIR
        self._data_dir.mkdir(parents=True, exist_ok=True)
        self._database = str(self._data_dir / TEACHING_DB)
        self._on_change = on_change
        self._lock = threading.Lock()

    def set_on_change(self, on_change: OnTeachingChange | None) -> None:
        self._on_change = on_change

    def _connect(self, *, initialize: bool = False) -> sqlite3.Connection:
        connection = sqlite3.connect(self._database if initialize else Path(self._database).as_uri() + "?mode=ro",
                                     uri=not initialize)
        if initialize:
            connection.execute(
                "CREATE TABLE IF NOT EXISTS teaching_units ("
                "id TEXT PRIMARY KEY, slug TEXT NOT NULL, status TEXT NOT NULL, "
                "data TEXT NOT NULL, updated_at TEXT NOT NULL)"
            )
            connection.commit()
        return connection

    # ---- 同步层（锁内执行）----

    def _save_sync(self, unit: TeachingUnit) -> None:
        data = unit.model_dump_json()
        with self._lock:
            connection = self._connect(initialize=True)
            try:
                connection.execute(
                    "INSERT INTO teaching_units (id, slug, status, data, updated_at) VALUES (?, ?, ?, ?, ?) "
                    "ON CONFLICT(id) DO UPDATE SET slug=excluded.slug, status=excluded.status, "
                    "data=excluded.data, updated_at=excluded.updated_at",
                    (unit.id, unit.slug, unit.status.value, data, unit.updated_at.isoformat()),
                )
                connection.commit()
            finally:
                connection.close()
        self._write_mirrors(unit)

    def _row_to_unit(self, row: tuple) -> TeachingUnit:
        return TeachingUnit.model_validate_json(str(row[0]))

    def _read_units(self, query: str, parameters: tuple = ()) -> list[TeachingUnit]:
        with self._lock:
            if not Path(self._database).exists():
                return []
            connection = self._connect()
            try:
                rows = connection.execute(query, parameters).fetchall()
            finally:
                connection.close()
        return [self._row_to_unit(row) for row in rows]

    def _get_active_sync(self) -> TeachingUnit | None:
        units = self._read_units(
            "SELECT data FROM teaching_units WHERE status = ? ORDER BY updated_at DESC LIMIT 1",
            (UnitStatus.active.value,),
        )
        return units[0] if units else None

    def _get_unit_sync(self, unit_id: str) -> TeachingUnit | None:
        units = self._read_units("SELECT data FROM teaching_units WHERE id = ?", (unit_id,))
        return units[0] if units else None

    def _list_units_sync(self) -> list[TeachingUnit]:
        return self._read_units("SELECT data FROM teaching_units ORDER BY rowid")

    # ---- 镜像：teaching/<slug>/spec.yaml + record.yaml ----

    def _write_mirrors(self, unit: TeachingUnit) -> None:
        unit_dir = self._data_dir / TEACHING_DIR / unit.slug
        unit_dir.mkdir(parents=True, exist_ok=True)

        objectives = []
        for o in unit.objectives:
            objectives.append({
                "id": o.id,
                "text": o.text,
                "mastery_required": o.mastery_required.value,
                "mastery_achieved": _mastery_value(o.mastery_achieved),
            })

        # 一锅 zones 按 kind 分成三组（kind 本身已体现在分组名里，导出时扔掉）
        agent_zone = []
        cowork_zone = []
        human_zone = []
        for z in unit.zones:
            z_data = z.model_dump(mode="json", exclude={"kind"})
            if z.kind is ZoneKind.agent:
                agent_zone.append(z_data)
            elif z.kind is ZoneKind.cowork:
                cowork_zone.append(z_data)
            else:
                human_zone.append(z_data)

        spec = {
            "spec": {
                "title": unit.title,
                "version": unit.version,
                "status": unit.status.value,
                "objectives": objectives,
                "zones": {
                    "agent_zone": agent_zone,
                    "cowork_zone": cowork_zone,
                    "human_zone": human_zone,
                },
                "hint_policy": {"max_level": unit.hint_max_level, "escalation_rule": "逐级解锁，每级升级前须展示当前级尝试"},
                "assessment": {"method": "对话提问、情境回答与追问反馈", "version": unit.version},
                "changelog": unit.changelog,
            }
        }

        hints = []
        for h in unit.hints:
            hints.append(h.model_dump(mode="json"))

        mastery = {}
        for o in unit.objectives:
            mastery[o.id] = _mastery_value(o.mastery_achieved)

        record = {
            "learning_record": {
                "unit": unit.slug,
                "hints": hints,
                "mastery": mastery,
                "created_at": unit.created_at.isoformat(),
                "updated_at": unit.updated_at.isoformat(),
            }
        }

        (unit_dir / "spec.yaml").write_text(_yaml_dump(spec), encoding="utf-8")
        (unit_dir / "record.yaml").write_text(_yaml_dump(record), encoding="utf-8")

    # ---- 异步公开 API（同步函数扔到后台线程跑，避免卡住事件循环）----

    async def save_unit(self, unit: TeachingUnit) -> TeachingUnit:
        """新建或更新单元（upsert），随后重写镜像并触发 on_change。"""
        unit.updated_at = _now()
        snapshot = unit.model_copy(deep=True)
        await anyio.to_thread.run_sync(self._save_sync, snapshot)
        await self._notify(snapshot)
        return snapshot

    async def get_active(self) -> TeachingUnit | None:
        return await anyio.to_thread.run_sync(self._get_active_sync)

    async def get_unit(self, unit_id: str) -> TeachingUnit | None:
        return await anyio.to_thread.run_sync(self._get_unit_sync, unit_id)

    async def list_units(self) -> list[TeachingUnit]:
        return await anyio.to_thread.run_sync(self._list_units_sync)

    async def _notify(self, unit: TeachingUnit) -> None:
        if self._on_change is None:
            return
        callback = self._on_change

        async def notify() -> None:
            result = callback(unit)
            if inspect.isawaitable(result):
                await result

        deferred = pending_notifications.get()
        if deferred is not None:
            deferred.append(notify)
        else:
            await notify()


# ---- store 注册表：工具集与动态指令共享同一实例 ----

_stores: dict[Path, TeachingStore] = {}


def teaching_store_for(workspace: Path | str, on_change: OnTeachingChange | None = None) -> TeachingStore:
    """按工作区获取或创建教学存储，并应用传入的回调。"""
    key = Path(workspace).expanduser().resolve()
    store = _stores.get(key)
    if store is None:
        store = TeachingStore(key, on_change=on_change)
        _stores[key] = store
    elif on_change is not None:
        store.set_on_change(on_change)
    return store


# ---- 渲染：teach_state 与动态指令共用 ----

def render_unit_state(unit: TeachingUnit) -> str:
    """把单元状态渲染成紧凑文本（动态指令与 teach_state 共用）。"""
    lines = [f"Unit: {unit.title} (id={unit.id}, slug={unit.slug}, v{unit.version}, {unit.status.value})"]

    lines.append("Objectives:")
    for o in unit.objectives:
        if o.mastery_achieved is None:
            achieved = "-"
        else:
            achieved = o.mastery_achieved.value
        used = _used_hint_levels(unit, o.id)
        if used:
            hint_text = ", hints used: " + _format_hint_levels(used)
        else:
            hint_text = ", hints used: 0"
        lines.append(f"  - {o.id} [required {o.mastery_required.value}, achieved {achieved}{hint_text}] {o.text}")

    lines.append("Dialogue zones: agent = AI explanations/examples; cowork = discussion; human = independent answers")
    for z in unit.zones:
        line = f"  - [{z.kind.value}] {z.scope}"
        if z.serves:
            line += " (serves " + ", ".join(z.serves) + ")"
        if z.note:
            line += " — " + z.note
        lines.append(line)

    lines.append(f"Assessment: dialogue answers and follow-up evidence (v{unit.version}; requirement changes require teach_bump_version first)")
    if unit.changelog:
        lines.append("Changelog: " + "; ".join(unit.changelog))
    return "\n".join(lines)


def render_closed_summary(units: list[TeachingUnit]) -> str:
    """最近关闭单元的一行式列表（teach_state 在无活跃单元时展示）。"""
    closed = []
    for u in units:
        if u.status is UnitStatus.closed:
            closed.append(u)
    if not closed:
        return ""
    lines = ["Recently closed units:"]
    for u in closed[-5:]:
        done = 0
        for o in u.objectives:
            if o.mastery_achieved is not None:
                done += 1
        lines.append(f"  - {u.title} (slug={u.slug}, v{u.version}): {done}/{len(u.objectives)} objectives mastered")
    return "\n".join(lines)


# ---- 工具集 ----

def teaching_toolset(store: TeachingStore) -> FunctionToolset:
    """对话教学单元、逐级提示与掌握度认证工具集。"""

    async def teach_create_unit(
        slug: str,
        title: str,
        objectives: list[Objective],
        zones: list[Zone],
        hint_max_level: int = 3,
    ) -> str:
        """在学习者对话确认 spec 后创建教学单元；每个工作区同时支持一个活跃单元。

        Args:
            slug: 文件系统安全标识（小写字母/数字/单连字符），记录目录名
            title: 单元标题
            objectives: 学习目标列表，id 唯一，text 描述能力与对话验收要求，各带 mastery_required
            zones: 对话三区：agent 讲解示例 / cowork 协作讨论 / human 独立回答；至少包含一个 human 区，serves 引用已有 objective id
            hint_max_level: 允许的最高提示级别（1-3，默认 3）
        """
        if await store.get_active() is not None:
            return "创建失败：已有一个活跃单元。先 teach_close_unit 关闭它，或继续当前单元。"
        if not _is_valid_slug(slug):
            return f"创建失败：slug {slug!r} 需要符合小写字母或数字开头、后续小写字母/数字/连字符、总长 1-50 的格式。"
        if hint_max_level < 1 or hint_max_level > 3:
            return "创建失败：hint_max_level 必须在 1-3 之间。"
        if not objectives:
            return "创建失败：至少需要一个学习目标。"

        seen_ids = []
        duplicate_ids = []
        for o in objectives:
            if o.id in seen_ids and o.id not in duplicate_ids:
                duplicate_ids.append(o.id)
            seen_ids.append(o.id)
        if duplicate_ids:
            return "创建失败：objective id 重复：" + ", ".join(duplicate_ids) + "。"

        has_human_zone = False
        for z in zones:
            if z.kind is ZoneKind.human:
                has_human_zone = True
        if not has_human_zone:
            return "创建失败：zones 中需要至少一个 human 区，承载用户独立回答的学习目标。"

        for z in zones:
            unknown = []
            for s in z.serves:
                if s not in seen_ids and s not in unknown:
                    unknown.append(s)
            if unknown:
                return f"创建失败：{z.kind.value} 区 {z.scope!r} 的 serves 需要关联已有 objective，请核对：" + ", ".join(unknown) + "。"

        unit = TeachingUnit(
            slug=slug, title=title, objectives=objectives, zones=zones,
            hint_max_level=hint_max_level,
        )
        await store.save_unit(unit)
        return (
            f"单元已创建：{unit.title}（id={unit.id}）。\n\n"
            "下一步：根据各项目标和验收要求准备问题与情境，在聊天中展示学习目标、对话三区与回答要求，"
            "提出第一个问题并等待用户回答，再依据真实回答给出追问与反馈。"
            "用户可用文字、解释或粘贴代码作答；代码通过阅读与推演讨论。\n\n"
            + render_unit_state(unit)
        )

    async def teach_state() -> str:
        """读取当前教学单元的对话验收状态。"""
        unit = await store.get_active()
        if unit is None:
            summary = render_closed_summary(await store.list_units())
            text = "当前可创建新的教学单元。"
            if summary:
                text += "\n\n" + summary
            return text
        return render_unit_state(unit)

    async def teach_record_hint(objective_id: str, level: int, attempt: str) -> str:
        """记录真实回答尝试并逐级提供提示，从 L1 开始，每次升级一级，保存到项目教学记录。

        Args:
            objective_id: 提示针对的 objective id
            level: 提示级别：L1 指方向 / L2 给推理结构 / L3 给类比示例，范围为 1 至 hint_max_level
            attempt: 学习者在当前级的真实回答或思考过程，须包含具体内容
        """
        unit = await store.get_active()
        if unit is None:
            return "记录失败：请先创建教学单元。"
        objective = _find_objective(unit, objective_id)
        if objective is None:
            ids = []
            for o in unit.objectives:
                ids.append(o.id)
            return f"记录失败：objective {objective_id!r} 需要选自现有目标：" + ", ".join(ids) + "。"
        if not attempt.strip():
            return "记录失败：attempt 为空。请记录学习者在当前级的真实回答或思考过程。"
        if level < 1 or level > unit.hint_max_level:
            return f"记录失败：level 须在 1-{unit.hint_max_level} 之间（本单元最高 L{unit.hint_max_level}）。"

        used = _used_hint_levels(unit, objective_id)
        highest = 0
        for lv in used:
            if lv > highest:
                highest = lv
        if level > highest + 1:
            return (
                f"记录失败：{objective_id} 目前最高用到 L{highest}，"
                f"下一提示级别为 L{highest + 1}。请先提供 L{highest + 1} 级别的提示。"
            )

        unit.hints.append(HintEvent(objective_id=objective_id, level=level, attempt=attempt.strip()))
        await store.save_unit(unit)

        if level == 1:
            allowance = "L1 指方向：给出相关概念、资料章节或引导思考的问题。"
        elif level == 2:
            allowance = "L2 给结构：给出推理框架或思考步骤，具体答案由学习者完成。"
        else:
            allowance = "L3 给对照：讲解相近主题的已解决示例，再请学习者迁移到当前问题。"
        all_used = used + [level]
        return (
            f"已记录：{objective_id} 提示级别 L{level}（该目标累计提示 {_format_hint_levels(all_used)}）。\n"
            "本级别的帮助：" + allowance
        )

    async def teach_update_mastery(objective_id: str, achieved: Mastery, evidence: str) -> str:
        """依据对话回答与追问证据认证掌握等级，等级递增；D1 要求独立作答，提示记录限于 L1/L2。

        Args:
            objective_id: 目标 id
            achieved: 已达成的等级：D1 独立解题 / D2 解释机制与理由 / D3 迁移到新情境
            evidence: 包含具体问题、用户真实回答与追问表现的验收依据
        """
        unit = await store.get_active()
        if unit is None:
            return "认证失败：请先创建教学单元。"
        objective = _find_objective(unit, objective_id)
        if objective is None:
            ids = []
            for o in unit.objectives:
                ids.append(o.id)
            return f"认证失败：objective {objective_id!r} 需要选自现有目标：" + ", ".join(ids) + "。"
        if not evidence.strip():
            return "认证失败：evidence 为空。请记录具体问题、用户真实回答和追问表现。"

        if objective.mastery_achieved is not None:
            if _MASTERY_ORDER[achieved] < _MASTERY_ORDER[objective.mastery_achieved]:
                return f"认证失败：{objective_id} 已达成 {objective.mastery_achieved.value}，请维持或提升已有等级。"

        used = _used_hint_levels(unit, objective_id)
        used_l3 = False
        for lv in used:
            if lv >= 3:
                used_l3 = True
        if achieved is Mastery.D1 and used_l3:
            return (
                f"认证失败：{objective_id} 已使用 L3 类比示例提示；D1 要求独立完成对话验收，提示记录限于 L1/L2。"
                "可依据机制与理由的讲解证据申请 D2 认证。"
            )

        objective.mastery_achieved = achieved
        await store.save_unit(unit)
        return f"已认证：{objective_id} 达成 {achieved.value}（要求 {objective.mastery_required.value}）。依据：{evidence.strip()}"

    async def teach_bump_version(reason: str) -> str:
        """升 spec 版本并记录变更理由；调整已确认验收要求或对话三区前调用。

        Args:
            reason: 调整已确认验收要求或对话三区的具体理由
        """
        unit = await store.get_active()
        if unit is None:
            return "升版失败：请先创建教学单元。"
        if not reason.strip():
            return "升版失败：reason 为空。请记录具体变更理由。"

        old = unit.version
        unit.version += 1
        now_text = _now().isoformat(timespec="seconds")
        unit.changelog.append(f"v{old}→v{unit.version} ({now_text}): {reason.strip()}")
        await store.save_unit(unit)
        return f"spec 已升到 v{unit.version}，变更理由已记录：{reason.strip()}。现在可以调整已确认验收要求或对话三区。"

    async def teach_close_unit(summary: str) -> str:
        """关闭当前单元并返回学习总结。

        Args:
            summary: 包含已掌握内容、待加强知识点与下一步建议的单元总结
        """
        unit = await store.get_active()
        if unit is None:
            return "关闭失败：请先创建教学单元。"
        if not summary.strip():
            return "关闭失败：summary 为空。请写清已掌握内容与待加强知识点。"

        unit.status = UnitStatus.closed
        await store.save_unit(unit)

        mastered_count = 0
        for o in unit.objectives:
            if o.mastery_achieved is not None:
                mastered_count += 1
        return (
            f"单元已关闭：{unit.title}（v{unit.version}，掌握 {mastered_count}/{len(unit.objectives)}）。\n"
            f"总结：{summary.strip()}\n"
            "请在聊天中向学习者说明已验收目标、真实回答证据、待加强知识点和下一步建议。"
        )

    return FunctionToolset(
        tools=[
            teach_create_unit,
            teach_state,
            teach_record_hint,
            teach_update_mastery,
            teach_bump_version,
            teach_close_unit,
        ],
        id="teaching",
    )
