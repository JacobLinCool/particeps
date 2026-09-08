#!/usr/bin/env python3
"""Reproduce the explicitly uncalibrated study energy scenarios using stdlib only.

This is a conditional workload calculation, not a Pixel hardware emulator.
All operation energies exclude shared awake-idle energy; see the companion report.
"""

import argparse
import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INPUT = ROOT / "docs/pixel-10a-energy-assumptions.json"


def nonnegative(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{name} must be numeric")
    if not math.isfinite(value) or value < 0:
        raise ValueError(f"{name} must be finite and nonnegative")
    return value


def calculate(data, scenario, coefficients):
    w = data["workload"]
    d = data["device"]
    c = coefficients
    hours = w["hours"]
    seconds = hours * 3600
    days = hours / 24
    gyro = scenario["gyro"]
    polling = scenario["polling"]
    vpn = scenario["vpn"]
    awake_extra = scenario["extra_awake_fraction"]
    if awake_extra > 1 - w["baseline_awake_fraction"] + 1e-12:
        raise ValueError("Extra awake time exceeds time otherwise available for suspend")
    if gyro and not math.isclose(awake_extra, 1 - w["baseline_awake_fraction"]):
        raise ValueError("Current continuous gyro requires the whole non-suspend interval")

    # Actual timer delivery is delayed by suspend. This is an explicit workload
    # input, not a guarantee that Android wakes for each requested polling period.
    poll_fraction = scenario["realized_poll_fraction"]
    event_count = sum(w["callback_events_per_day"].values()) * days
    gyro_count = w["actual_gyro_hz"] * seconds if gyro else 0
    throughput_polls = seconds / w["throughput_interval_seconds"] * poll_fraction if polling else 0
    usage_polls = seconds / w["usage_interval_seconds"] * poll_fraction if polling else 0
    usage_events = w["usage_events_per_day"] * days if polling else 0
    audits = seconds / w["vpn_audit_interval_seconds"] * scenario["realized_audit_fraction"] if vpn else 0
    control_commits = w["other_control_commits_per_day"] * days
    commits = event_count + gyro_count + throughput_polls + usage_polls + audits + control_commits

    # Explicit count-limited workload: each modeled commit is <16 KiB. The
    # 1 MiB threshold therefore cannot precede the 64-commit threshold.
    # Adding forced checkpoints is a conservative budget: actual forced resets
    # can replace some of these periodic checkpoints.
    snapshots = math.ceil(commits / w["checkpoint_commits"]) + w["forced_snapshot_budget"]
    energy = {
        "runtime_and_access_checks": c["runtime_mw"] * hours,
        "shared_extra_awake": awake_extra * c["awake_increment_mw"] * hours,
        "gyro_sensor_and_hub": c["gyro_sensor_mw"] * hours if gyro else 0,
        "event_processing": (event_count + gyro_count + throughput_polls + usage_events) * c["event_mj"] / 3600,
        "throughput_queries": throughput_polls * c["throughput_query_mj"] / 3600,
        "usage_queries": usage_polls * c["usage_query_mj"] / 3600,
        "durable_commits": commits * c["commit_mj"] / 3600,
        "snapshots": snapshots * c["snapshot_mj"] / 3600,
        "vpn_service": c["vpn_service_mw"] * hours if vpn else 0,
        "vpn_audits": audits * c["vpn_audit_mj"] / 3600,
        "vpn_forwarding": 0,
        "vpn_connections_and_keepalive": 0,
        "limiter_processing": 0,
        "treatment_radio_delta": 0,
        "survey_ui": 0,
    }
    if vpn:
        mb = w["vpn_mb_per_day"] * days
        packets = mb * 1_000_000 / w["mean_packet_bytes"]
        energy["vpn_forwarding"] = (mb * c["vpn_mj_per_mb"] + packets * c["vpn_mj_per_packet"]) / 3600
        energy["vpn_connections_and_keepalive"] = hours * (
            w["connections_per_hour"] * c["connection_mj"]
            + w["keepalive_probes_per_hour"] * c["keepalive_mj"]
        ) / 3600
        energy["limiter_processing"] = c["limiter_extra_mw"] * w["limited_hours"]
        energy["treatment_radio_delta"] = c["limited_radio_delta_mw"] * w["limited_hours"]
    if scenario["surveys"]:
        energy["survey_ui"] = w["survey_count"] * w["survey_minutes"] / 60 * c["survey_ui_mw"]
    total = sum(energy.values())
    capacity_mwh = d["capacity_mah"] * d["assumed_voltage_v"]
    return {
        "scenario": scenario["id"],
        "energy_mwh": total,
        "average_mw": total / hours,
        "equivalent_mah": total / d["assumed_voltage_v"],
        "capacity_percent_over_study": total / capacity_mwh * 100,
        "capacity_percentage_points_per_24h": total / capacity_mwh * 100 / days,
        "work_counts": {"commits": commits, "snapshot_budget": snapshots,
                        "gyro_events": gyro_count, "throughput_polls": throughput_polls,
                        "usage_polls": usage_polls, "vpn_audits": audits},
        "components_mwh": energy,
    }


def validate(data):
    d, w = data["device"], data["workload"]
    for name in ("capacity_mah", "assumed_voltage_v"):
        if nonnegative(d[name], name) == 0:
            raise ValueError(f"{name} must be positive")
    for name, value in w.items():
        if name == "callback_events_per_day":
            for source, count in value.items():
                nonnegative(count, source)
        else:
            nonnegative(value, name)
    for name in ("hours", "throughput_interval_seconds", "usage_interval_seconds",
                 "vpn_audit_interval_seconds", "mean_packet_bytes", "checkpoint_commits"):
        if w[name] == 0:
            raise ValueError(f"{name} must be positive")
    if w["baseline_awake_fraction"] > 1 or w["limited_hours"] > w["hours"]:
        raise ValueError("Invalid awake fraction or treatment duration")
    if w["checkpoint_commits"] != 64:
        raise ValueError("This model represents the implemented 64-commit checkpoint policy")
    if not 0 < w["maximum_modeled_frame_bytes"] <= 1_048_576 / 64:
        raise ValueError("Frame budget could trigger the byte checkpoint earlier than modeled")
    for name, values in data["coefficient_scenarios"].items():
        for key, value in values.items():
            # Radio treatment can either save or consume energy.
            if key == "limited_radio_delta_mw":
                if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                    raise ValueError("Radio delta must be finite")
            else:
                nonnegative(value, f"{name}.{key}")
    for scenario in data["feature_scenarios"]:
        for flag in ("gyro", "polling", "vpn", "surveys"):
            if not isinstance(scenario[flag], bool):
                raise ValueError(f"{flag} must be boolean")
        for name in ("extra_awake_fraction", "realized_poll_fraction", "realized_audit_fraction"):
            if not 0 <= nonnegative(scenario[name], name) <= 1:
                raise ValueError(f"{name} must be a fraction")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assumptions", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--json", action="store_true", help="Print full results and all input assumptions")
    args = parser.parse_args()
    data = json.loads(args.assumptions.read_text())
    validate(data)
    results = {
        name: [calculate(data, scenario, values) for scenario in data["feature_scenarios"]]
        for name, values in data["coefficient_scenarios"].items()
    }
    if args.json:
        print(json.dumps({"calibration_status": "uncalibrated_conditional_scenarios",
                          "inputs": data, "results": results}, indent=2, ensure_ascii=False, allow_nan=False))
    else:
        print("UNCALIBRATED conditional scenarios — not Pixel 10a measurements or confidence bounds.")
        print("| Features | Coefficients | Average mW | Study mWh | Capacity pp / 24h | Capacity % / study |")
        print("| --- | --- | ---: | ---: | ---: | ---: |")
        for name, rows in results.items():
            for row in rows:
                print(f"| {row['scenario']} | {name} | {row['average_mw']:.2f} | "
                      f"{row['energy_mwh']:.1f} | {row['capacity_percentage_points_per_24h']:.2f} | "
                      f"{row['capacity_percent_over_study']:.2f} |")


if __name__ == "__main__":
    main()
