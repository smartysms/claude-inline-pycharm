# Inline AI Prompt

[![Build](https://github.com/smartysms/claude-inline-pycharm/actions/workflows/build.yml/badge.svg)](https://github.com/smartysms/claude-inline-pycharm/actions/workflows/build.yml)

A PyCharm plugin that brings an inline chat to the editor, backed by your local Claude Code CLI (`claude -p`).
Press a shortcut, optionally type an instruction, pick a thinking level, and Claude rewrites the selection,
the enclosing function, the blank line or the current line in place. The result is applied to the file and shown
as an inline diff that you accept or reject. Each round is one undoable edit.

The inline chat was designed after inspecting GitHub Copilot's inline chat in PyCharm: an input panel embedded in the
editor at the target, a Processing state with a Stop button, the result applied as an inline diff with Accept / Reject,
errors shown as a banner inside the panel, follow-up requests in the same panel, and slash commands.

## What it looks like

Illustrative sketch (not a screenshot): you press the shortcut on a comment, the panel appears above it, and the
generated code is applied with the new lines highlighted.

```
  | Claude  Comment (1 line)                       [ Ultrathink v ]  x |
  | Describe the change, or press Enter to implement it...             |
  |   (o) Thinking (Ultrathink) - 14s                        [ Stop ]  |

  # fetch user by id, cache the result
+ def get_user(user_id: int) -> User:
+     cached = redis.get(f"user:{user_id}")
+     if cached:
+         return User.model_validate_json(cached)
+     user = db.query(User).get(user_id)
+     redis.setex(f"user:{user_id}", 300, user.model_dump_json())
+     return user

  [ Accept ]  [ Reject ]  Show diff        Esc = reject, Ctrl+Enter = accept
```

## Quick start

1. Install and log in to the [Claude Code CLI](https://docs.claude.com/en/docs/claude-code) (run `claude` once).
2. Build the plugin (see [Development](#development)) or take the zip from a GitHub release, then
   Settings | Plugins | gear icon | Install Plugin from Disk...
3. In a Python file, put the caret on a `# comment`, a blank line or inside a function and press
   `Ctrl+Alt+Shift+G` (write the code from the comment or the function name), or `Ctrl+Alt+Shift+K` (type an instruction).

How it works internally is described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Requirements

- PyCharm 2026.2 (build 262) or newer. The plugin declares `since-build 262` and no upper bound.
  It depends on the bundled Python plugin (`PythonCore`).
- The Claude Code CLI installed and logged in (run `claude` once in a terminal to log in). The plugin only shells out
  to it; it never touches your credentials.

## Install

Settings | Plugins | gear icon | Install Plugin from Disk..., then choose the zip (from a release, or from
`build/distributions/` after building) and restart the IDE.

## Usage

There are two actions. Both are also in the editor context menu.

| Action | Shortcut | What it does |
| --- | --- | --- |
| Inline AI: Prompt... | Ctrl+Alt+Shift+K | Opens the inline panel at the target, where you type an instruction. |
| Inline AI: Implement from Comment / Name | Ctrl+Alt+Shift+G | Starts immediately, without an input step, using the default thinking level from settings. |

### What Claude edits (the target)

The target is picked in this order, first match wins:

1. A non-empty selection: exactly the selected text (its leading indentation is included).
2. A comment-only line: the whole contiguous block of comment lines. If a function starts on the line right below the
   comment block, the comment and the whole function are the target.
3. A blank line: Claude inserts new code at that position.
4. The Python function that contains the caret (decorators included).
5. Otherwise the current line.

The panel header shows the target, for example "Selection (3 lines)", "Function parse_csv (lines 12-30)",
"Insert at line 14" or "Line 7".

### Zero-prompt flows

You do not have to type an instruction. An empty input (just Enter), or the Implement action, makes Claude work from
what is already in the file:

- Write a comment such as `# fetch user by id, cache the result` and press the shortcut on the blank line below it or
  on the comment line itself. The code that implements the comment appears below it and the comment is kept.
- Put the caret in or on a function such as `def get_user(user_id): pass` and press the shortcut. The function is
  implemented from its name, signature, type hints, docstring and comments.
- Put the caret in an existing function and press the shortcut. The function is adjusted to match what its name,
  docstring and comments say, keeping behavior that already matches.

### The inline panel

The panel sits between the editor lines, above the target. It has three states:

- Input: type an instruction. Enter runs it (an empty input runs the automatic instruction for the target), Shift+Enter
  inserts a newline, Ctrl+Up / Ctrl+Down changes the thinking level, Esc closes the panel without changing anything.
- Processing: the input is disabled and the panel shows "Thinking (<level>)" with the elapsed seconds and a Stop button.
  Stop (or Esc) cancels the request.
- Review: Claude's result is already applied to the file and shown as an inline diff (new lines highlighted, removed
  lines shown above them). Accept (button or Ctrl+Enter) keeps it. Reject (button or Esc) restores the original text; the
  rejection is itself an undoable edit, so Ctrl+Z brings Claude's result back. "Show diff" opens a side-by-side diff.
  The input stays enabled, so you can type a follow-up to refine the result in the same panel.

Errors (Claude failed, the CLI was not found, the code changed while Claude was working) appear as a banner inside the
panel, and your instruction text is kept so you can retry.

### Slash commands

Typing `/` at the start of the input lists the shortcuts below; you can add guidance after the command, for example
`/fix handle None`.

| Command | Purpose |
| --- | --- |
| `/doc` | Add or improve documentation |
| `/fix` | Find and fix problems |
| `/optimize` | Improve performance |
| `/simplify` | Simplify the code |
| `/types` | Add or correct type hints |

### Thinking levels

The level is chosen in the panel (and defaults from settings). It adds a keyword line to the prompt and passes
`--effort` to the CLI:

| Level | `--effort` | Keyword added to the prompt |
| --- | --- | --- |
| Default | (none) | (none) |
| Think | `medium` | `think` |
| Think hard | `high` | `think hard` |
| Think harder | `xhigh` | `think harder` |
| Ultrathink | `max` | `ultrathink` |

Higher levels are slower and cost more.

## Project patterns

Tools | Inline AI: Analyze Project Patterns (also in the editor context menu) lets Claude study the whole project once and
write down its conventions: how APIs are structured, how the database is used, how Redis is used, cache-aside and
similar patterns. The result is saved to `.inline-ai/project-patterns.md` in the project root. Every later edit
request includes this profile, so generated code matches your style.

- The file is plain Markdown: edit it by hand to correct or extend it, and commit it so the team shares it.
- To refresh it, run the action again; it overwrites the file.
- The analysis uses read-only tools (Read, Grep, Glob) only, never edits anything, costs tokens and can take
  several minutes. It runs in the background and can be cancelled from the progress bar.
- It ignores dependency and build folders such as `venv`, `.venv`, `node_modules`, `.git` and `__pycache__`.
- The inline panel shows "Using project patterns (updated <date>)" when a profile is applied, or a hint to run the
  analysis when there is none. Turn this off with "Use project patterns" in the settings.

## Settings

Settings | Tools | Inline AI Prompt.

- Claude executable: path of the `claude` executable. If it is blank or does not exist, the plugin searches `PATH`
  (`claude.exe`, `.cmd`, `.bat`, `claude`), then `~/.local/bin`, then `%APPDATA%\npm`.
- Model: passed as `--model`; leave empty to use the CLI default.
- Isolated mode: passes `--safe-mode`, so Claude skips CLAUDE.md files, hooks, MCP servers and skills. This is faster and
  makes results independent of your personal Claude setup. Turn it off if you want your CLAUDE.md rules applied.
- Allow read-only project tools: by default Claude gets no tools at all (`--tools ""`, text in and text out). When enabled it may read
  other files of the project with `Read`, `Grep` and `Glob` (still no edits).
- Use project patterns: inject the saved profile into every edit request (default on).
- Timeout: seconds to wait for Claude before giving up (default 600).
- Default thinking level.

The CLI is run as `claude -p --output-format text --tools "" --safe-mode --effort <level> --no-session-persistence`
with the whole prompt on standard input.

## Development

Gradle wrapper 9.8.2, IntelliJ Platform Gradle Plugin 2.19.0, Kotlin 2.3.21. The platform jars are Java 25 class
files, so Gradle must run on Java 25 (PyCharm's bundled JBR is one: set `JAVA_HOME` to `<PyCharm>/jbr`).

```bash
./gradlew build          # compile, test and package: build/distributions/*.zip
./gradlew test           # unit and platform tests
./gradlew runIde         # sandbox PyCharm with the plugin installed
```

By default the build downloads PyCharm (`platformVersion` in `gradle.properties`) from JetBrains. To build against an
IDE you already have installed, set `platformPath` outside the repository, in `~/.gradle/gradle.properties`
(Windows: `%USERPROFILE%\.gradle\gradle.properties`):

```properties
platformPath=C:/Program Files/JetBrains/PyCharm
```

or pass it once: `./gradlew build -PplatformPath="C:/Program Files/JetBrains/PyCharm"`.

Two tests call the real Claude CLI and only run with `CLAUDE_CLI_IT=1` set. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design and [CHANGELOG.md](CHANGELOG.md) for releases.

## Legal notes

- This is an independent, unofficial project. It is not affiliated with, endorsed by or sponsored by Anthropic or
  JetBrains. "Claude" and "Claude Code" are trademarks of Anthropic, PBC.
- The plugin does not call any Anthropic API itself and does not include or modify Claude Code. It runs the
  Claude Code CLI that you installed yourself, unmodified, on your own machine. Authentication is whatever you set up
  in that CLI; the plugin never reads, stores or forwards your credentials or tokens.
- Your use of Claude Code stays subject to Anthropic's terms and
  [Usage Policy](https://www.anthropic.com/legal/aup) (see the
  [Claude Code legal page](https://code.claude.com/docs/en/legal-and-compliance)). Anthropic's rules on using
  Claude subscription logins from third-party tools have changed over time; if you are unsure, authenticate the CLI
  with an API key from the Claude Console.
- Prompts include the code around your selection (and the project patterns file, if enabled), and are sent to
  Anthropic by the CLI. Do not use it on code you are not allowed to share with Anthropic.

## License

[MIT](LICENSE).

## Troubleshooting

- The plugin cannot find the Claude executable: set the full path in Settings | Tools | Inline AI Prompt (for example
  `C:\Users\<you>\.local\bin\claude.exe`), or add the `claude` folder to `PATH` and restart the IDE.
- Claude fails with an authentication error: run `claude` once in a terminal and log in, then retry.
- It takes a long time: lower the thinking level, or select a smaller region. Ultrathink can take minutes; raise the
  timeout in settings if requests are cut off.
- "Claude did not return a replacement": the reply was not in the expected format. Retry, or rephrase the instruction.
- "The code changed while Claude was working; result discarded": the target text was edited while the request ran.
  Wait for the result before editing the region again.
- A shortcut does nothing or opens something else: another plugin or keymap uses it. Search for "Claude" in
  Settings | Keymap and rebind the two actions.
- Build errors about class file versions: build with the IDE's bundled JBR (Java 25) as `JAVA_HOME`.
