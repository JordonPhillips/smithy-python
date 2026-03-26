#  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
#  SPDX-License-Identifier: Apache-2.0

import pytest
from smithy_core.aio.retries import SimpleRetryStrategy
from smithy_core.exceptions import CallError, RetryError
from smithy_core.retries import ExponentialRetryBackoffStrategy



