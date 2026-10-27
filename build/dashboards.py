#!/usr/bin/env python3
"""
The validator for the suite's dashboards.

A dashboard is a different resource from a LogicModule: it is imported through
Dashboards > Add > From File rather than My Module Toolbox. The dashboards are the
portal's own exports, kept in dashboards/<Name>.json (user, 2026-10-27): they were
designed in the portal, so the portal's export is the source and nothing generates
them. build.py runs check_dashboard over each before copying it into dist/.

WHERE THE SCHEMA COMES FROM
---------------------------
Every key name, every enum value and every nesting shape below was taken from
LogicMonitor's own published dashboard exports rather than guessed:

    https://github.com/logicmonitor/dashboards  (Apache-2.0)
    Virtualization/Hyper-V.json     cgraph, dynamicTable, noc, alert
    Virtualization/Nutanix.json     bigNumber

plus the keys a current portal adds to its own export (PORTAL_EXPORT_KEYS). An export
format that is almost right imports cleanly and then renders nothing, and a renamed
module or datapoint leaves a widget that imports and stays empty; this catches both
before an import does.
"""

from __future__ import annotations

# Widget config key sets, verbatim from the reference exports. A widget must carry all
# of these and nothing else beyond PORTAL_EXPORT_KEYS -- an unexpected key is as likely
# to break the import as a missing one, and neither shows up as an error in the portal.
REFERENCE_KEYS: dict[str, set[str]] = {
    "cgraph": {
        "description", "displaySettings", "graphInfo", "interval", "name", "theme",
        "timescale", "type", "version",
    },
    "dynamicTable": {
        "columns", "dataSourceFullName", "description", "displaySettings", "forecast",
        "interval", "name", "rows", "sortOrder", "theme", "timescale", "topX", "type",
        "version",
    },
    "bigNumber": {
        "bigNumberInfo", "description", "displaySettings", "interval", "name", "theme",
        "timescale", "type", "version",
    },
    "noc": {
        "ackChecked", "description", "displayColumn", "displayCriticalAlert",
        "displayErrorAlert", "displaySettings", "displayWarnAlert", "interval", "items",
        "name", "sdtChecked", "sortBy", "theme", "timescale", "type", "version",
    },
    "alert": {
        "description", "displaySettings", "filters", "interval", "name", "theme",
        "timescale", "type", "version",
    },
}

# Keys a current portal writes into its own export (santabaRelease 246, these dashboards
# exported 2026-10-27) on top of the reference set. "*" applies to every widget type.
PORTAL_EXPORT_KEYS: dict[str, set[str]] = {
    "*": {"isSupportCustomProperty", "supportCustomProperty"},
    "noc": {"displayClearedAlert"},
}

# Enum vocabularies observed in the reference exports. Anything outside these is an
# invention, and inventions are what fail to import.
THEMES = ("newSolidDarkBlue", "newBorderDarkBlue")
TIMESCALES = ("8hour", "12hour", "day", "2days", "7days")
DISPLAY_TYPES = ("line", "area", "stack")
# A dynamicTable column renders as one of these. "number" is NOT one of them -- this
# project invented it, and a portal drops the whole widget rather than reporting it.
COLUMN_DISPLAY_TYPES = ("percent", "raw")
# topX values the reference exports use. A cgraph takes 10 or 25; a dynamicTable takes
# 25 or -1, which means every row. Anything else is an invention, and 20 on a cgraph was
# confirmed in a portal to drop the widget.
CGRAPH_TOP_X = (10, 25)
TABLE_TOP_X = (25, -1)
# Every dynamicTable column in the reference exports carries these bounds, including the
# "raw" ones whose values run far past 100 (Nutanix graphs IOPs this way). They are read
# for the percent bar and ignored otherwise, so they are fixed rather than per-column:
# varying them is one of the things that made a portal refuse to create the widget.
COLUMN_MIN_VALUE = 0
COLUMN_MAX_VALUE = 100
# The portal truncates a dashboard description past this, and says nothing about it.
DESCRIPTION_MAX_CHARS = 256

# The grid every LogicMonitor dashboard lays out on.
GRID_COLUMNS = 12


# ------------------------------------------------------------------- validation

def check_dashboard(label: str, dashboard: dict,
                    modules: dict[str, set[str]]) -> list[str]:
    """
    Cross-check a dashboard export against the module definitions and the reference
    schema. `modules` maps "<displayedAs> (<name>)" to that module's datapoint names.

    The expensive failure this guards against is a module or datapoint rename: the
    dashboard keeps importing, the widgets keep rendering, and every one of them is
    empty. Nothing in the portal reports it.
    """
    problems: list[str] = []
    references: list[tuple[str, str, str]] = []

    description = dashboard.get("description", "")
    if len(description) > DESCRIPTION_MAX_CHARS:
        problems.append(f"{label}: description is {len(description)} characters; the "
                        f"portal truncates past {DESCRIPTION_MAX_CHARS} without saying so")

    for widget in dashboard["widgets"]:
        config = widget["config"]
        kind = config.get("type")
        name = config.get("name", "<unnamed>")

        if kind not in REFERENCE_KEYS:
            problems.append(f"{label}: {name!r} has widget type {kind!r}, which appears "
                            f"in no reference export")
            continue
        expected = REFERENCE_KEYS[kind]
        allowed = expected | PORTAL_EXPORT_KEYS.get(kind, set()) | PORTAL_EXPORT_KEYS["*"]
        if not expected <= set(config) <= allowed:
            extra = sorted(set(config) - allowed)
            missing = sorted(expected - set(config))
            problems.append(f"{label}: {name!r} ({kind}) key set differs from the "
                            f"reference export -- extra={extra} missing={missing}")
        if config.get("theme") not in THEMES:
            problems.append(f"{label}: {name!r} theme {config.get('theme')!r} is not one "
                            f"the reference exports use")
        if config.get("timescale") not in TIMESCALES:
            problems.append(f"{label}: {name!r} timescale {config.get('timescale')!r} is "
                            f"not one the reference exports use")

        if kind == "cgraph":
            if config["graphInfo"]["topX"] not in CGRAPH_TOP_X:
                problems.append(f"{label}: {name!r} graph topX "
                                f"{config['graphInfo']['topX']!r} is not one the "
                                f"reference exports use {CGRAPH_TOP_X}")
            for series in config["graphInfo"]["dataPoints"]:
                references.append((series["dataSourceFullName"],
                                   series["dataPointName"], name))
                if series["display"]["type"] not in DISPLAY_TYPES:
                    problems.append(f"{label}: {name!r} display type "
                                    f"{series['display']['type']!r} is not one the "
                                    f"reference exports use")
        elif kind == "dynamicTable":
            if config["topX"] not in TABLE_TOP_X:
                problems.append(f"{label}: {name!r} table topX {config['topX']!r} is "
                                f"not one the reference exports use {TABLE_TOP_X}")
            for column in config["columns"]:
                references.append((config["dataSourceFullName"],
                                   column["dataPointName"], name))
                problems.extend(_check_column(label, name, column))
        elif kind == "bigNumber":
            for series in config["bigNumberInfo"]["dataPoints"]:
                references.append((series["dataSourceFullName"],
                                   series["dataPointName"], name))

        if set(widget["position"]) != {"col", "sizex", "row", "sizey"}:
            problems.append(f"{label}: {name!r} has malformed position keys")

    for module, datapoint, where in references:
        if module not in modules:
            problems.append(f"{label}: {where!r} references unknown module {module!r}")
        elif datapoint not in modules[module]:
            problems.append(f"{label}: {where!r} references {module}, which declares no "
                            f"datapoint {datapoint!r}")

    problems.extend(_check_grid(label, dashboard["widgets"]))
    return problems


def _check_column(label: str, widget: str, column: dict) -> list[str]:
    """
    A dynamicTable column, against the reference exports field by field.

    This check exists because every one of these was got wrong at once, and a portal
    reported it only as "Some widgets could not be created due to incompatible version
    or configuration errors" -- on first load, naming no widget. The dashboard imported,
    the offending widgets were silently discarded, and the survivors looked perfect.
    """
    problems: list[str] = []
    where = f"{label}: {widget!r} column {column['columnName']!r}"
    if column["displayType"] not in COLUMN_DISPLAY_TYPES:
        problems.append(f"{where} has displayType {column['displayType']!r}, which "
                        f"appears in no reference export {COLUMN_DISPLAY_TYPES}")
    if not isinstance(column["colorThresholds"], list):
        problems.append(f"{where} has colorThresholds "
                        f"{column['colorThresholds']!r}; the reference exports always "
                        f"carry a list, empty when there are no thresholds")
    if column["minValue"] != COLUMN_MIN_VALUE or column["maxValue"] != COLUMN_MAX_VALUE:
        problems.append(f"{where} has bounds {column['minValue']!r}.."
                        f"{column['maxValue']!r}; every reference column is "
                        f"{COLUMN_MIN_VALUE}..{COLUMN_MAX_VALUE}")
    return problems


def _check_grid(label: str, widgets: list[dict]) -> list[str]:
    """Overlapping widgets render on top of each other rather than erroring."""
    problems: list[str] = []
    occupied: dict[tuple[int, int], str] = {}
    for widget in widgets:
        position = widget["position"]
        name = widget["config"].get("name", "<unnamed>")
        if position["col"] + position["sizex"] - 1 > GRID_COLUMNS:
            problems.append(f"{label}: {name!r} overflows the {GRID_COLUMNS}-column "
                            f"grid (col {position['col']} + {position['sizex']})")
        for row in range(position["row"], position["row"] + position["sizey"]):
            for col in range(position["col"], position["col"] + position["sizex"]):
                if (row, col) in occupied:
                    problems.append(f"{label}: {name!r} overlaps "
                                    f"{occupied[(row, col)]!r} at row {row} col {col}")
                occupied[(row, col)] = name
    return problems
