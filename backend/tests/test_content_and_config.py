from __future__ import annotations

import pytest
from pydantic import ValidationError


def test_non_https_and_untrusted_content_are_rejected():
    from atlas_backend.content import ContentItem

    with pytest.raises(ValidationError):
        ContentItem(id="bad-url", title="Lien", url="http://example.org")
    with pytest.raises(ValidationError):
        ContentItem(id="script", title="<script>alert(1)</script>", url="https://example.org")
    with pytest.raises(ValidationError):
        ContentItem(
            id="credentials",
            title="Lien", 
            url="https://user:pass@example.org/path",
        )


def test_capabilities_require_known_boolean_flags():
    from atlas_backend.policy import configured_capabilities

    assert configured_capabilities('{"focus_sessions": false}') ["focus_sessions"] is False
    with pytest.raises(ValueError):
        configured_capabilities('{"shell_access": true}')
    with pytest.raises(ValueError):
        configured_capabilities('{"pause": "yes"}')
