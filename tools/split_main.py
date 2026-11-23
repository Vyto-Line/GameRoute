#!/usr/bin/env python3
"""One-off clean-up (23 Nov 2026): moves MainActivity's functions, section by section, into
MainXxx.kt files as `internal fun MainActivity.name()` extensions. Member state, overrides and
inner classes stay in the class; its private members become internal so the new files reach them.

usage: python3 tools/split_main.py   (from the repo root)
"""
import re, sys

SRC = "app/src/main/java/gr/gamemap/MainActivity.kt"
# section header (text after the dashes) -> file
FILES = [
    ("location off", "MainLocation"),
    ("look helpers", None),
    ("search & quick places", "MainSearch"),
    ("pharmacies", "MainPharmacies"),
    ("fuel nearby", "MainFuel"),
    ("home sheet", "MainHome"),
    ("right-hand buttons", "MainDriving"),
    ("driving card", "MainDriving"),
    ("route preview", "MainDriving"),
    ("place card", "MainPlace"),
    ("GameRoute's own pop-ups", None),
    ("faster route offer", "MainArrival"),
    ("arrival card", "MainArrival"),
    ("state → UI", None),
    ("suggestions", "MainSuggestions"),
    ("bottom sheets", "MainSheets"),
    ("convoy", "MainConvoy"),
    ("the convoy, step by step", "MainConvoy"),
    ("friends", "MainFriends"),
    ("the invite card", "MainInvite"),
    ("lifecycle & compass", None),
]


def scan(s):
    """Depth of braces at each index, skipping strings, chars and comments."""
    depth = [0] * (len(s) + 1)
    d = 0; i = 0; n = len(s)
    stack = []  # string template nesting: brace depth at which a ${ opened
    in_str = None  # None, '"', '"""'
    while i < n:
        ch = s[i]
        if in_str:
            if s.startswith("${", i):
                stack.append((in_str, d)); in_str = None; d += 1; depth[i] = d; depth[i + 1] = d; i += 2; continue
            if in_str == '"' and ch == "\\": depth[i] = d; depth[i + 1] = d; i += 2; continue
            if s.startswith(in_str, i):
                L = len(in_str); in_str = None
                for k in range(L): depth[i + k] = d
                i += L; continue
            depth[i] = d; i += 1; continue
        if s.startswith("//", i):
            j = s.find("\n", i); j = n if j < 0 else j
            for k in range(i, j): depth[k] = d
            i = j; continue
        if s.startswith("/*", i):
            j = s.find("*/", i) + 2
            for k in range(i, j): depth[k] = d
            i = j; continue
        if s.startswith('"""', i): in_str = '"""'; depth[i] = depth[i+1] = depth[i+2] = d; i += 3; continue
        if ch == '"': in_str = '"'; depth[i] = d; i += 1; continue
        if ch == "'":
            j = i + 1
            if s[j] == "\\": j += 1
            j = s.find("'", j + 1) + 1
            for k in range(i, j): depth[k] = d
            i = j; continue
        if ch == "{": d += 1; depth[i] = d; i += 1; continue
        if ch == "}":
            if stack and stack[-1][1] == d - 1:
                in_str, _ = stack.pop(); d -= 1; depth[i] = d; i += 1; continue
            d -= 1; depth[i] = d; i += 1; continue
        depth[i] = d; i += 1
    return depth


def main():
    s = open(SRC).read()
    lines = s.split("\n")
    offs = [0]
    for ln in lines: offs.append(offs[-1] + len(ln) + 1)
    depth = scan(s)
    cls_start = next(i for i, ln in enumerate(lines) if ln.startswith("class MainActivity"))
    cls_end = next(i for i in range(cls_start + 1, len(lines)) if lines[i] == "}")
    lvl = lambda li: depth[offs[li]]  # depth at the start of the line
    # members: lines inside the class at depth 1 that begin a declaration
    decl = re.compile(r"^    (?:@[\w.]+(?:\([^)]*\))?\s+)*((?:(?:private|internal|public|protected|override|open|inline|inner|lateinit|const|data|enum|abstract|suspend|tailrec|operator|infix)\s+)*)(fun|val|var|class|object|init|companion|interface|enum|data|sealed|typealias|inner)\b")
    members = []
    i = cls_start + 1
    section = None
    while i < cls_end:
        ln = lines[i]
        m = re.match(r"^    // -+ (.*)$", ln)
        if m and lvl(i) == 1: section = m.group(1).strip(); i += 1; continue
        if lvl(i) == 1 and decl.match(ln):
            # back up over KDoc / comments / annotations right above
            st = i
            while st - 1 > cls_start and (lines[st - 1].strip().startswith(("*", "/**", "/*", "//", "@")) and not re.match(r"^    // -+ ", lines[st - 1])) and lvl(st - 1) == 1:
                st -= 1
            # end: the declaration ends where depth returns to 1 at the start of a line after it
            j = i + 1
            # a one-liner (= expression) may continue on indented lines
            while j < cls_end and (lvl(j) > 1 or (lines[j].startswith("        ") and not lines[j].strip() == "")):
                j += 1
            members.append((st, j, section, ln))
            i = j; continue
        i += 1
    def target_of(sec):
        if sec is None: return None
        best = None
        for k, f in FILES:
            if sec == k or sec.startswith(k + " "): best = f if best is None or len(k) > 0 else best
        return best
    moved = {}
    keep_ranges = []
    for st, en, sec, head in members:
        f = target_of(sec)
        m = decl.match(head)
        mods, kind = m.group(1), m.group(2)
        body = "\n".join(lines[st:en])
        if (f is None or kind != "fun" or "override" in mods or "protected" in mods or "super." in body or "@JvmStatic" in body
                or "open" in mods.split()):
            continue
        name = re.search(r"\bfun\s+(?:<[^>]*>\s+)?(\w+)", head).group(1)
        newbody = re.sub(r"^(    (?:@[\w.]+(?:\([^)]*\))?\s+)*)((?:private|internal|public)\s+)?(inline\s+)?fun\s+(<[^>]*>\s+)?(\w+)",
                         lambda mm: f"{mm.group(1)}internal {mm.group(3) or ''}fun {mm.group(4) or ''}MainActivity.{mm.group(5)}", body, count=1, flags=re.M)
        newbody = newbody.replace("this@MainActivity", f"this@{name}")
        # de-indent one level
        newbody = "\n".join(l[4:] if l.startswith("    ") else l for l in newbody.split("\n"))
        moved.setdefault(f, []).append(newbody)
        keep_ranges.append((st, en))
    drop = set()
    for st, en in keep_ranges: drop.update(range(st, en))
    rest = [ln for k, ln in enumerate(lines) if k not in drop]
    out = "\n".join(rest)
    # what stays in the class: private members become internal (the new files reach them)
    out_lines = out.split("\n")
    for k, ln in enumerate(out_lines):
        out_lines[k] = re.sub(r"^(    (?:@[\w.]+(?:\([^)]*\))?\s+)*)private (?=(?:inner |lateinit |inline |data |enum |const )*(?:fun|val|var|class|object|inner|lateinit|data|enum|const)\b)", r"\1internal ", ln)
        out_lines[k] = re.sub(r"^(\s+)private set\b", r"\1internal set", out_lines[k])
        out_lines[k] = re.sub(r"^private (fun|val|var|class|object)\b", r"internal \1", out_lines[k])
    out = "\n".join(out_lines)
    out = re.sub(r"\n{3,}", "\n\n", out)
    open(SRC, "w").write(out)
    header = s[: s.index("\nclass MainActivity")]
    header = header[: header.rfind("\n/**")] if "\n/**" in header[-2000:] else header
    imports = "\n".join(l for l in header.split("\n") if l.startswith("import ") or l.startswith("package "))
    for f, bodies in moved.items():
        open(f"app/src/main/java/gr/gamemap/{f}.kt", "w").write(
            f"@file:Suppress(\"unused\")\n\n{imports}\n\n// Part of MainActivity, moved out in the clean-up of 23 Nov 2026 (see tools/split_main.py).\n\n" + "\n\n".join(bodies) + "\n")
        print(f, len(bodies), "functions")
    print("MainActivity now", out.count("\n"), "lines")


main()
