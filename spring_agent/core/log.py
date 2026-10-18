import os
import sys
from pathlib import Path

from loguru import logger as _raw_loguru_logger

from spring_agent.core.config.settings import log

LOG_DIRECTORY = Path.home() / ".springagent"
LOG_FILE = LOG_DIRECTORY / "app.log"


def set_log_level(level: str = "DEBUG") -> None:
    _raw_loguru_logger.remove()
    if os.environ.get("SPRINGAGENT_LOG_STDOUT") == "1":
        _raw_loguru_logger.add(sys.stderr, level=level)
    else:
        LOG_DIRECTORY.mkdir(parents=True, exist_ok=True)
        _raw_loguru_logger.add(
            LOG_FILE,
            rotation="10 MB",
            retention="10 days",
            encoding="utf-8",
            level=level,
        )


set_log_level(log.log_level)

logger = _raw_loguru_logger
