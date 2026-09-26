"""
Builds app/src/main/assets/foods.json from foods_spec.py, after validating it.

USDA values come from FoodData Central "SR Legacy" (public domain):
  https://fdc.nal.usda.gov/download-datasets  ->  FoodData_Central_sr_legacy_food_csv_2018-04.zip
The values used are cached in usda_cache.json, so the zip is only needed when adding USDA ids.

Usage:
  python tools/fooddb/build_foods.py                      # build from usda_cache.json
  python tools/fooddb/build_foods.py path/to/sr_legacy.zip  # refresh the cache from the zip, then build
  python tools/fooddb/build_foods.py --check              # validate only, write nothing
"""
import csv
import io
import json
import re
import sys
import zipfile
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from foods_spec import FOODS  # noqa: E402

UNITS = {"plate", "bowl", "cup", "glass", "piece", "slice", "tbsp", "tsp", "handful", "scoop", "serving", "can"}
DEFAULT_ONLY_UNITS = {"g"}  # a default portion may be given in grams without listing "g" as a unit
CATEGORIES = {
    "grain", "ricedish", "bread", "poultry", "meat", "fish", "egg", "dairy", "legume", "vegetable", "leafy", "fruit",
    "citrus", "grape", "nuts", "fat", "sweet", "dessert", "snack", "curry", "fastfood", "pizza", "drink", "hotdrink",
    "packaged", "other",
}
SOURCES = {"usda", "fct", "local"}
REGIONS = {None, "bd", "in", "pk", "lk", "np", "sa"}
NEEDS_BANGLA = {"bd", "sa"}  # Bangladeshi (and shared South Asian) dishes must be findable in Bangla script
NUTRIENTS = {"1008": "kcal", "1003": "p", "1005": "c", "1004": "f"}
CACHE = HERE / "usda_cache.json"
TARGET = HERE.parents[1] / "app/src/main/assets/foods.json"
MAX_BYTES = 250 * 1024
BANGLA = re.compile("[ঀ-৿]")


def load_usda_zip(zip_path, wanted):
    z = zipfile.ZipFile(zip_path)
    prefix = next(n for n in z.namelist() if n.endswith("food_nutrient.csv")).rsplit("/", 1)[0] + "/"
    values = {fdc: {} for fdc in wanted}
    with z.open(prefix + "food_nutrient.csv") as raw:
        for row in csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8")):
            key = NUTRIENTS.get(row["nutrient_id"])
            fdc = row["fdc_id"]
            if key and fdc in values:
                values[fdc][key] = float(row["amount"])
    out = {}
    for fdc, n in values.items():
        missing = {"kcal", "p", "c", "f"} - n.keys()
        if missing:
            raise SystemExit(f"USDA {fdc} missing {missing}")
        out[fdc] = [n["kcal"], n["p"], n["c"], n["f"]]
    return out


def write_cache(values):
    lines = [f'"{k}": {json.dumps(v)}' for k, v in sorted(values.items())]
    CACHE.write_text("{\n" + ",\n".join(lines) + "\n}\n", encoding="utf-8")


def food_id(f):
    if f["src"] == "usda":
        return f"usda-{f['fdc']}"
    slug = "".join(ch if ch.isalnum() else "-" for ch in f["name"].lower()).strip("-")
    return f"nt-{slug}"


def validate(foods, usda):
    """Returns a list of errors; an empty list means the spec is good."""
    errors, ids, names = [], Counter(), Counter()
    for f in foods:
        name = f["name"]
        ids[food_id(f)] += 1
        names[name.lower()] += 1
        if f["cat"] not in CATEGORIES:
            errors.append(f"{name}: unknown category {f['cat']!r}")
        if f["src"] not in SOURCES:
            errors.append(f"{name}: unknown source {f['src']!r}")
        if f.get("region") not in REGIONS:
            errors.append(f"{name}: unknown region {f.get('region')!r}")
        bad = set(f["units"]) - UNITS
        if bad:
            errors.append(f"{name}: unknown units {bad}")
        if any(not (isinstance(v, (int, float)) and v > 0) for v in f["units"].values()):
            errors.append(f"{name}: unit weights must be positive numbers")
        d = f["default"]
        if not d:
            errors.append(f"{name}: missing default portion")
        elif d[0] not in f["units"] and d[0] not in DEFAULT_ONLY_UNITS:
            errors.append(f"{name}: default unit {d[0]!r} not in units")
        elif not d[1] > 0:
            errors.append(f"{name}: default quantity must be positive")
        if f.get("region") in NEEDS_BANGLA and not any(BANGLA.search(a) for a in f["aliases"]):
            errors.append(f"{name}: Bangladeshi dish without a Bangla-script alias")
        if len(set(a.lower() for a in f["aliases"])) != len(f["aliases"]):
            errors.append(f"{name}: duplicate alias")

        if f["src"] == "usda":
            if str(f["fdc"]) not in usda:
                errors.append(f"{name}: USDA {f['fdc']} not in cache - run with the SR Legacy zip")
                continue
            kcal, p, c, fat = usda[str(f["fdc"])]
        else:
            kcal, p, c, fat = f["kcal"], f["p"], f["c"], f["f"]
            if not (0 <= kcal <= 900) or min(p, c, fat) < 0 or p + c + fat > 101:
                errors.append(f"{name}: impossible values {kcal} kcal, {p}/{c}/{fat} g")
        # Atwater check. USDA uses food-specific factors (fibre-rich produce reads low), so it only warns there;
        # a small absolute slack keeps near-zero foods (tea, leafy greens) from tripping on rounding.
        calc = 4 * p + 4 * c + 9 * fat
        if abs(calc - kcal) > max(0.15 * kcal, 10):
            msg = f"{name}: {kcal} kcal but macros give {calc:.0f}"
            if f["src"] == "usda":
                print("warning:", msg)
            else:
                errors.append(msg)
    errors += [f"duplicate id {i}" for i, n in ids.items() if n > 1]
    errors += [f"duplicate name {n}" for n, k in names.items() if k > 1]
    return errors


def build_entry(f, usda):
    if f["src"] == "usda":
        kcal, p, c, fat = usda[str(f["fdc"])]
    else:
        kcal, p, c, fat = f["kcal"], f["p"], f["c"], f["f"]
    entry = {
        "id": food_id(f),
        "n": f["name"],
        "k": f["cat"],
        "e": round(kcal, 1), "p": round(p, 2), "c": round(c, 2), "f": round(fat, 2),
        # Liquids list their household units in ml (a 330 ml can); the app stores grams.
        "u": {k: round(v * f["ml"], 1) for k, v in f["units"].items()} if f["ml"] else f["units"],
        "a": f["aliases"],
        "s": f["src"],
    }
    if f["default"]:
        entry["d"] = list(f["default"])
    if f["ml"]:
        entry["ml"] = f["ml"]
    return entry


def main(args):
    check_only = "--check" in args
    zips = [a for a in args if not a.startswith("--")]
    usda = json.loads(CACHE.read_text(encoding="utf-8")) if CACHE.exists() else {}
    if zips:
        usda_ids = {str(f["fdc"]) for f in FOODS if f["src"] == "usda"}
        usda = load_usda_zip(zips[0], usda_ids)
        if not check_only:
            write_cache(usda)

    errors = validate(FOODS, usda)
    if errors:
        print("\n".join(errors))
        raise SystemExit(f"{len(errors)} problem(s) in foods_spec.py")

    regions = Counter(f.get("region") or "generic" for f in FOODS)
    cats = Counter(f["cat"] for f in FOODS)
    print("regions:", dict(regions.most_common()))
    print("categories:", dict(cats.most_common()))
    if check_only:
        print(f"OK: {len(FOODS)} foods")
        return

    out = [build_entry(f, usda) for f in FOODS]
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    size = TARGET.stat().st_size
    print(f"Wrote {len(out)} foods ({sum(1 for e in out if e['s'] == 'usda')} USDA) to {TARGET} ({size // 1024} KB)")
    if size > MAX_BYTES:
        raise SystemExit(f"foods.json is {size // 1024} KB, over the {MAX_BYTES // 1024} KB budget")


if __name__ == "__main__":
    main(sys.argv[1:])
