# Drishti Engines · first-party engines by contract.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""The worker package of the ``python`` engine: the pure-Python Arrow codec, the engine contract, the worker loop the pool manager starts,
the sandbox, the items runtime (measure, column, transform, visual, cell) and the pivot-function runtime, behind one class,
``engine_python.engine:PythonEngine``. Standard library only for the transport; pandas, NumPy, pyarrow and the others only as the
site's approved, pinned environment provides them. Runs in any interpreter of 3.10 or later."""

__version__ = "1.0.0"

from .arrow import Column, Table  # noqa: E402
from .contract import ComputeError, Engine, Inputs, Progress, Request, Result, register_cache  # noqa: E402

__all__ = ["Column", "ComputeError", "Engine", "Inputs", "Progress", "Request", "Result", "Table", "__version__", "register_cache"]
