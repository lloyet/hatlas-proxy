// PreToolUse hook (Bash, filtered on `gh pr create`): blocks opening a PR whose plugin
// changes have not passed the in-game suites (Open Question 20).
//
// Allowed when the change touches no feature, or when
// a verdict in .claude/state/ingame/<sha>.json (written by the ingame-tester agent) —
// for the PR's head (--head/-H, else the checked-out HEAD), or for an ancestor of it with no src/main/ change since (only tests or
// docs were committed after the run) — says
// verdict PASS — or WAIVED, only when the user explicitly asked to skip — for every
// affected feature, and no src/main/ file is left uncommitted.
// Otherwise denies the call and tells Claude to run the ingame-tester agent first.
import { execSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { affectedFeatures, parsePrCommand, resolveHead } from "./affected-features.mjs";

const deny = (reason) => {
  process.stdout.write(JSON.stringify({
    hookSpecificOutput: { hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: reason },
  }));
};

let raw = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (c) => (raw += c));
process.stdin.on("end", () => {
  let input;
  try { input = JSON.parse(raw); } catch { return; }
  const cmd = input?.tool_input?.command ?? "";
  const segments = cmd.split(/&&|\|\||;|\||\n/).map((s) => s.trim());
  if (!segments.some((s) => /^gh\s+pr\s+create\b/.test(s))) return;

  const root = input?.cwd || process.cwd();
  // Judge the PR's own branch (--head), not whatever the main checkout has checked out.
  const { base, head: headFlag } = parsePrCommand(cmd);
  const pr = resolveHead(headFlag, root);
  let a;
  try { a = affectedFeatures(base, root, pr.ref); } catch { return; } // no map / not a repo: let it through
  if (a.features.length === 0) return;

  let dirtySrc = "";
  if (pr.current) {
    try { dirtySrc = execSync("git status --porcelain -- src/main/", { cwd: root, encoding: "utf8" }).trim(); } catch {}
  }
  if (dirtySrc) {
    return deny("In-game gate: commit the src/main/ changes first; the in-game suites must run on the "
      + "exact commit that the PR will contain. Then run the ingame-tester agent and retry.");
  }

  const ask = `Run the 'ingame-tester' agent in the FOREGROUND (Agent tool, subagent_type "ingame-tester") `
    + `with: "Verify features [${a.features.join(", ")}] for ${pr.current ? "HEAD" : `branch ${pr.branch} at`} ${a.head.slice(0, 7)} `
    + `(diff origin/${base}...${pr.current ? "HEAD" : pr.branch}). Missing suites to write first: [${a.missingSuites.join(", ") || "none"}]. `
    + `Uncovered files to map in features.yml: [${a.uncovered.join(", ") || "none"}]." `
    + `Fix any FAIL, commit, re-run, then retry gh pr create.`;
  const v = findVerdict(root, a.head);
  const where = pr.current ? "HEAD" : `branch ${pr.branch}`;
  if (!v) return deny(`In-game gate: no in-game verdict for ${where} ${a.head.slice(0, 7)} `
    + `(nor for an ancestor with the same src/main/). ${ask}`);
  const ok = v.verdict === "PASS" || v.verdict === "WAIVED";
  const missing = a.features.filter((f) => !(v.features ?? []).includes(f));
  if (!ok) return deny(`In-game gate: verdict for ${where} ${a.head.slice(0, 7)} is ${v.verdict}. ${ask}`);
  if (missing.length) return deny(`In-game gate: verdict does not cover [${missing.join(", ")}]. ${ask}`);
  // Allowed: say so in the transcript.
  process.stdout.write(JSON.stringify({
    systemMessage: `In-game gate: ${v.verdict} for [${a.features.join(", ")}] at ${where} ${a.head.slice(0, 7)}.`,
  }));
});

/** Verdict for HEAD, else the newest one for an ancestor of HEAD with identical src/main/. */
function findVerdict(root, head) {
  const dir = join(root, ".claude/state/ingame");
  if (!existsSync(dir)) return null;
  const git = (args) => { try { execSync(`git ${args}`, { cwd: root, stdio: "ignore" }); return true; } catch { return false; } };
  const candidates = [];
  for (const f of readdirSync(dir)) {
    if (!f.endsWith(".json")) continue;
    let v;
    try { v = JSON.parse(readFileSync(join(dir, f), "utf8")); } catch { continue; }
    if (!v?.head) continue;
    if (v.head === head) return v;
    candidates.push(v);
  }
  for (const v of candidates.reverse()) {
    if (git(`merge-base --is-ancestor ${v.head} ${head}`) && git(`diff --quiet ${v.head} ${head} -- src/main/`)) return v;
  }
  return null;
}
