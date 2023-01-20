# Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"). You
# may not use this file except in compliance with the License. A copy of
# the License is located at
#
#     http://aws.amazon.com/apache2.0/
#
# or in the "license" file accompanying this file. This file is
# distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
# ANY KIND, either express or implied. See the License for the specific
# language governing permissions and limitations under the License.


def split_header(given: str, handle_unquoted_http_date: bool = False) -> list[str]:
    """Splits a header value into a list of strings.

    The format is based on RFC9110's list extension found in secion 5.6.1 with quoted
    the quoted string syntax found in section 5.6.4. In short:

    A list is 1 or more elements surrounded by optional whitespace and separated by
    commas. Elements may be quoted with double quotes (``"``) to contain leading or
    trailing whitespace, commas, or double quotes. Inside the the double quotes, a
    value may be escaped with a backslash (``\\``). Elements that contain no contents
    are ignored.

    If the list is known to contain unquoted IMF-fixdate formatted timestamps, the
    ``handle_unquoted_http_date`` parameter can be set to ensure the list isn't
    split on the commas inside the timestamps.

    :param given: The header value to split.
    :param handle_unquoted_http_date: Support splitting IMF-fixdate lists without
        quotes. Defaults to False.
    :returns: The header value split on commas.
    """
    result: list[str] = []

    i = 0
    while i < len(given):
        if given[i].isspace():
            # Skip any leading space.
            i += 1
        elif given[i] == '"':
            # Grab the contents of the quoted value and append it.
            entry, i = _consume_until(given, i + 1, '"', escape="\\")
            result.append(entry)

            if given[i - 1] != '"':
                raise ValueError(
                    f"Invalid header list syntax: expected end quote but reached end "
                    f"of value: `{given}`"
                )

            # Skip until the next comma.
            excess, i = _consume_until(given, i, ",")
            if excess.strip():
                raise ValueError(
                    f"Invalid header list syntax: Found quote contents after "
                    f"end-quote: `{excess}` in `{given}`"
                )
        else:
            entry, i = _consume_until(
                given, i, ",", skip_first=handle_unquoted_http_date
            )
            if stripped := entry.strip():
                result.append(stripped)

    return result


def _consume_until(
    given: str,
    start: int,
    until: str,
    skip_first: bool = False,
    escape: str | None = None,
) -> tuple[str, int]:
    should_skip = skip_first
    end = start
    result = ""
    escaped = False
    while end < len(given):
        if escaped:
            result += given[end]
            escaped = False
        elif given[end] == escape:
            escaped = True
        elif given[end] == until:
            if should_skip:
                result += given[end]
                should_skip = False
            else:
                break
        else:
            result += given[end]
        end += 1
    return result, end + 1
