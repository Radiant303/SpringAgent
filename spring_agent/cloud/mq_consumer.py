"""MQ 消费者的独立运行入口（调试 MQ 链路用）。

消费者通常由引擎进程内启动；本模块仅保留独立调试能力：
python -m spring_agent.cloud.mq_consumer
"""

import asyncio

from spring_agent.cloud.mq import _main

if __name__ == "__main__":
    asyncio.run(_main())
