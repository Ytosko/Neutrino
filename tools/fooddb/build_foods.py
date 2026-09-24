"""
Builds app/src/main/assets/foods.json from foods_spec.py.

USDA values come from FoodData Central "SR Legacy" (public domain):
  https://fdc.nal.usda.gov/download-datasets  ->  FoodData_Central_sr_legacy_food_csv_2018-04.zip

Usage:
  python tools/fooddb/build_foods.py path/to/FoodData_Central_sr_legacy_food_csv_2018-04.zip
"""
import csv
import io
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from foods_spec import FOODS  # noqa: E402

UNITS = {"plate", "bowl", "cup", "glass", "piece", "slice", "tbsp", "tsp", "handful", "scoop", "serving", "can"}
NUTRIENTS = {"1008": "kcal", "1003": "p", "1005": "c", "1004": "f"}


def load_usda(zip_path, wanted):
    z = zipfile.ZipFile(zip_path)
    prefix = next(n for n in z.namelist() if n.endswith("food_nutrient.csv")).rsplit("/", 1)[0] + "/"
    values = {fdc: {} for fdc in wanted}
    with z.open(prefix + "food_nutrient.csv") as raw:
        for row in csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8")):
            key = NUTRIENTS.get(row["nutrient_id"])
            fdc = row["fdc_id"]
            if key and fdc in values:
                values[fdc][key] = float(row["amount"])
    return values


def main(zip_path):
    usda_ids = {str(f["fdc"]) for f in FOODS if f["src"] == "usda"}
    usda = load_usda(zip_path, usda_ids)

    out, seen = [], set()
    for f in FOODS:
        if f["name"] in seen:
            raise SystemExit(f"Duplicate name: {f['name']}")
        seen.add(f["name"])
        bad = set(f["units"]) - UNITS
        if bad:
            raise SystemExit(f"{f['name']}: unknown units {bad}")
        if f["default"] and f["default"][0] not in f["units"] and f["default"][0] != "g":
            raise SystemExit(f"{f['name']}: default unit not in units")

        if f["src"] == "usda":
            n = usda[str(f["fdc"])]
            missing = {"kcal", "p", "c", "f"} - n.keys()
            if missing:
                raise SystemExit(f"{f['name']}: USDA {f['fdc']} missing {missing}")
            food_id, kcal, p, c, fat = f"usda-{f['fdc']}", n["kcal"], n["p"], n["c"], n["f"]
        else:
            slug = "".join(ch if ch.isalnum() else "-" for ch in f["name"].lower()).strip("-")
            food_id, kcal, p, c, fat = f"nt-{slug}", f["kcal"], f["p"], f["c"], f["f"]

        entry = {
            "id": food_id,
            "n": f["name"],
            "k": f["cat"],
            "e": round(kcal, 1), "p": round(p, 2), "c": round(c, 2), "f": round(fat, 2),
            "u": f["units"],
            "a": f["aliases"],
            "s": f["src"],
        }
        if f["default"]:
            entry["d"] = list(f["default"])
        if f["ml"]:
            entry["ml"] = f["ml"]
        out.append(entry)

    target = Path(__file__).resolve().parents[2] / "app/src/main/assets/foods.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {len(out)} foods ({sum(1 for e in out if e['s'] == 'usda')} USDA) to {target} ({target.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main(sys.argv[1])
