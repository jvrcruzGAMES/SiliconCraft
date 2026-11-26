"""Preserve GitHub-style heading anchors used by the repository Markdown."""
import re
import unicodedata


def github_slug(value, separator):
    value = value.strip().lower()
    value = "".join(
        char for char in value
        if char in " -_" or unicodedata.category(char)[0] in "LN"
    )
    return re.sub(r"\s", separator, value)


def on_config(config):
    config["mdx_configs"]["toc"]["slugify"] = github_slug
    return config
