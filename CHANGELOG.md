# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0]

### Added
- Inline chat panel embedded in the editor, backed by the local Claude Code CLI (`claude -p`).
- Targets: selection, comment block, blank line, enclosing Python function, current line.
- Zero-prompt generation: write code from a comment, or from a function's name, signature and docstring
  (`Ctrl+Alt+Shift+G`, or an empty input in the panel).
- Thinking levels (Default, Think, Think hard, Think harder, Ultrathink) mapped to the CLI's `--effort`.
- Result applied as an undoable edit and shown as an inline diff with Accept, Reject, Show diff and follow-ups.
- Slash commands: `/doc`, `/fix`, `/optimize`, `/simplify`, `/types`.
- Automatic insertion of missing Python imports.
- Project pattern analysis (API style, DB access, Redis usage, cache-aside) saved to
  `.inline-ai/project-patterns.md` and used in later requests.
- Settings page: executable, model, isolated mode, read-only tools, project patterns, timeout, default level.

[Unreleased]: https://github.com/smartysms/claude-inline-pycharm/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/smartysms/claude-inline-pycharm/releases/tag/v0.1.0
