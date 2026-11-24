#!/usr/bin/env node
// Validates catalog/catalog.json with the same rules the admin dashboard uses.
//
//   node tools/catalog/validate_catalog.mjs [catalog/catalog.json] [--json report.json]
//
// Exits 1 when the catalog has errors. Warnings are printed but do not fail the run.
// In GitHub Actions every problem is also written as an annotation.
import { readFileSync, writeFileSync } from "node:fs";
import { validateCatalog } from "../../dashboard/js/schema.js";

const args = process.argv.slice(2);
const jsonAt = args.indexOf("--json");
const reportFile = jsonAt >= 0 ? args[jsonAt + 1] : null;
const file = args.find((a, i) => !a.startsWith("--") && (jsonAt < 0 || i !== jsonAt + 1)) ?? "catalog/catalog.json";
const inActions = process.env.GITHUB_ACTIONS === "true";

let result;
try {
  const text = readFileSync(file, "utf8");
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (e) {
    result = { ok: false, errors: [{ path: "$", message: `not valid JSON: ${e.message}` }], warnings: [] };
  }
  if (!result) result = validateCatalog(parsed);
} catch (e) {
  result = { ok: false, errors: [{ path: "$", message: `cannot read ${file}: ${e.message}` }], warnings: [] };
}

// Annotation text must stay on one line and must not be able to inject workflow commands.
const clean = (s) => String(s).replace(/[\r\n%]/g, " ").replace(/::/g, ": :");
for (const w of result.warnings) {
  console.log(inActions ? `::warning file=${file}::${clean(w.path)}: ${clean(w.message)}` : `warning  ${w.path}: ${w.message}`);
}
for (const e of result.errors) {
  console.log(inActions ? `::error file=${file}::${clean(e.path)}: ${clean(e.message)}` : `error    ${e.path}: ${e.message}`);
}
console.log(result.ok ? `${file}: valid (${result.warnings.length} warning(s))` : `${file}: ${result.errors.length} error(s)`);
if (reportFile) writeFileSync(reportFile, JSON.stringify(result, null, 2) + "\n");
process.exit(result.ok ? 0 : 1);
