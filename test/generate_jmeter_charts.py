# -*- coding: utf-8 -*-
"""
根据 JMeter CSV 结果生成压测图表。

默认读取 test/results 目录下的：
- login_result.csv
- analysis_result.csv
- profile_result.csv
- all_summary.csv（可选，仅作兼容，不作为主数据源）
"""

from __future__ import annotations

import csv
import math
from collections import defaultdict
from pathlib import Path
from statistics import mean

import matplotlib.pyplot as plt
import numpy as np
from matplotlib import rcParams

rcParams["font.family"] = "Microsoft YaHei"
rcParams["axes.unicode_minus"] = False
rcParams["figure.dpi"] = 150

BASE_DIR = Path(__file__).resolve().parent
RESULTS_DIR = BASE_DIR / "results"
OUTPUT_DIR = BASE_DIR / "charts"
OUTPUT_DIR.mkdir(exist_ok=True)

COLOR_PRIMARY = "#4F81BD"
COLOR_SECONDARY = "#F28E2B"
COLOR_TERTIARY = "#59A14F"
COLOR_ERROR = "#C00000"
GRID_COLOR = "#D9D9D9"
BG_COLOR = "#FAFAFA"

RESULT_FILES = {
    "登录接口": RESULTS_DIR / "login_result.csv",
    "分析接口": RESULTS_DIR / "analysis_result.csv",
    "用户画像接口": RESULTS_DIR / "profile_result.csv",
}


def to_float(value, default=0.0):
    try:
        if value in (None, ""):
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def to_bool(value):
    return str(value).strip().lower() == "true"


def percentile(values, q):
    if not values:
        return 0.0
    arr = np.array(sorted(values), dtype=float)
    return float(np.percentile(arr, q))


def load_jmeter_csv(path: Path):
    rows = []
    if not path.exists():
        return rows

    with path.open("r", encoding="utf-8-sig", newline="") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            rows.append(
                {
                    "label": row.get("label", "").strip(),
                    "elapsed": to_float(row.get("elapsed")),
                    "timestamp": to_float(row.get("timeStamp")),
                    "success": to_bool(row.get("success")),
                    "bytes": to_float(row.get("bytes")),
                    "sent_bytes": to_float(row.get("sentBytes")),
                    "thread_name": row.get("threadName", "").strip(),
                }
            )
    return rows


def summarize_rows(rows):
    if not rows:
        return None

    elapsed = [r["elapsed"] for r in rows]
    success_count = sum(1 for r in rows if r["success"])
    min_ts = min(r["timestamp"] for r in rows if r["timestamp"])
    max_ts = max(r["timestamp"] for r in rows if r["timestamp"])
    duration_sec = max((max_ts - min_ts) / 1000.0, 1.0) if min_ts and max_ts else 1.0

    return {
        "samples": len(rows),
        "avg": mean(elapsed),
        "p90": percentile(elapsed, 90),
        "p95": percentile(elapsed, 95),
        "p99": percentile(elapsed, 99),
        "min": min(elapsed),
        "max": max(elapsed),
        "error_rate": (len(rows) - success_count) / len(rows) * 100,
        "throughput": len(rows) / duration_sec,
        "recv_kb_s": sum(r["bytes"] for r in rows) / 1024.0 / duration_sec,
    }


def summarize_by_label(rows):
    buckets = defaultdict(list)
    for row in rows:
        if row["label"]:
            buckets[row["label"]].append(row)
    return {label: summarize_rows(items) for label, items in buckets.items()}


def load_all_data():
    grouped = {}
    for name, path in RESULT_FILES.items():
        rows = load_jmeter_csv(path)
        if rows:
            grouped[name] = rows
    if not grouped:
        raise FileNotFoundError(
            f"未找到可用的 JMeter 结果文件，请先运行压测并确认结果输出到 {RESULTS_DIR}"
        )
    return grouped


def plot_thread_group_summary(grouped):
    scenario_names = []
    avgs = []
    p95s = []
    throughputs = []
    errors = []

    for scenario, rows in grouped.items():
        stats = summarize_rows(rows)
        scenario_names.append(scenario)
        avgs.append(stats["avg"])
        p95s.append(stats["p95"])
        throughputs.append(stats["throughput"])
        errors.append(stats["error_rate"])

    x = np.arange(len(scenario_names))
    width = 0.35

    fig, ax1 = plt.subplots(figsize=(11, 5.5))
    fig.patch.set_facecolor(BG_COLOR)
    ax1.set_facecolor(BG_COLOR)

    bars1 = ax1.bar(x - width / 2, avgs, width, label="平均响应时间 (ms)", color=COLOR_PRIMARY, zorder=3)
    bars2 = ax1.bar(x + width / 2, p95s, width, label="P95 响应时间 (ms)", color=COLOR_SECONDARY, zorder=3)

    ax2 = ax1.twinx()
    ax2.plot(x, throughputs, "o-", color=COLOR_TERTIARY, linewidth=2, markersize=7, label="吞吐量 (req/s)", zorder=4)
    ax2.plot(x, errors, "D--", color=COLOR_ERROR, linewidth=1.8, markersize=6, label="错误率 (%)", zorder=4)

    for bar in list(bars1) + list(bars2):
        ax1.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 5, f"{bar.get_height():.1f}",
                 ha="center", va="bottom", fontsize=8.5)

    ax1.set_title("各压测场景响应时间对比", fontsize=14, fontweight="bold", pad=12)
    ax1.set_ylabel("响应时间 (ms)")
    ax2.set_ylabel("吞吐量 / 错误率")
    ax1.set_xticks(x)
    ax1.set_xticklabels(scenario_names)
    ax1.grid(axis="y", color=GRID_COLOR, linewidth=0.8, zorder=0)
    ax1.spines["top"].set_visible(False)
    ax2.spines["top"].set_visible(False)

    handles1, labels1 = ax1.get_legend_handles_labels()
    handles2, labels2 = ax2.get_legend_handles_labels()
    ax1.legend(handles1 + handles2, labels1 + labels2, loc="upper left", framealpha=0.9)

    plt.tight_layout()
    out = OUTPUT_DIR / "fig7-5_response_time_comparison.png"
    plt.savefig(out, bbox_inches="tight")
    plt.close()
    print(f"[OK] 已生成：{out}")


def plot_all_api_summary(grouped):
    all_rows = []
    for rows in grouped.values():
        all_rows.extend(rows)

    summary = summarize_by_label(all_rows)
    labels = list(summary.keys())
    avg_rt = [summary[label]["avg"] for label in labels]
    throughput = [summary[label]["throughput"] for label in labels]
    error_rate = [summary[label]["error_rate"] for label in labels]

    x = np.arange(len(labels))
    width = 0.34

    fig, ax1 = plt.subplots(figsize=(13, 6))
    fig.patch.set_facecolor(BG_COLOR)
    ax1.set_facecolor(BG_COLOR)

    bars1 = ax1.bar(x - width / 2, avg_rt, width, label="平均响应时间 (ms)", color=COLOR_PRIMARY, zorder=3)
    bars2 = ax1.bar(x + width / 2, throughput, width, label="吞吐量 (req/s)", color=COLOR_SECONDARY, zorder=3)

    ax2 = ax1.twinx()
    ax2.plot(x, error_rate, "D--", color=COLOR_ERROR, linewidth=2, markersize=6, label="错误率 (%)", zorder=4)

    for bar in list(bars1) + list(bars2):
        ax1.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + max(bar.get_height() * 0.02, 1),
                 f"{bar.get_height():.1f}", ha="center", va="bottom", fontsize=8)

    ax1.set_title("各核心接口压测汇总", fontsize=14, fontweight="bold", pad=12)
    ax1.set_ylabel("响应时间 / 吞吐量")
    ax2.set_ylabel("错误率 (%)", color=COLOR_ERROR)
    ax2.tick_params(axis="y", colors=COLOR_ERROR)
    ax1.set_xticks(x)
    ax1.set_xticklabels(labels, rotation=18, ha="right")
    ax1.grid(axis="y", color=GRID_COLOR, linewidth=0.8, zorder=0)
    ax1.spines["top"].set_visible(False)
    ax2.spines["top"].set_visible(False)

    handles1, labels1 = ax1.get_legend_handles_labels()
    handles2, labels2 = ax2.get_legend_handles_labels()
    ax1.legend(handles1 + handles2, labels1 + labels2, loc="upper right", framealpha=0.9)

    plt.tight_layout()
    out = OUTPUT_DIR / "fig7-all-api-summary.png"
    plt.savefig(out, bbox_inches="tight")
    plt.close()
    print(f"[OK] 已生成：{out}")


def plot_response_time_over_test(grouped):
    all_rows = []
    for rows in grouped.values():
        all_rows.extend(rows)
    if not all_rows:
        return

    all_rows.sort(key=lambda row: row["timestamp"])
    base_ts = all_rows[0]["timestamp"]
    second_buckets = defaultdict(list)

    for row in all_rows:
        second = int(max((row["timestamp"] - base_ts) / 1000.0, 0))
        second_buckets[second].append(row["elapsed"])

    xs = sorted(second_buckets.keys())
    ys = [mean(second_buckets[x]) for x in xs]
    window = min(5, len(ys))
    if window >= 2:
        kernel = np.ones(window) / window
        ys = np.convolve(ys, kernel, mode="same")

    fig, ax = plt.subplots(figsize=(12, 5))
    fig.patch.set_facecolor(BG_COLOR)
    ax.set_facecolor(BG_COLOR)

    ax.plot(xs, ys, color=COLOR_PRIMARY, linewidth=2.0, label="每秒平均响应时间", zorder=3)
    ax.fill_between(xs, ys, color=COLOR_PRIMARY, alpha=0.08, zorder=2)

    ax.set_title("压测过程中响应时间变化曲线", fontsize=14, fontweight="bold", pad=12)
    ax.set_xlabel("测试时间 (秒)")
    ax.set_ylabel("响应时间 (ms)")
    ax.grid(True, color=GRID_COLOR, linewidth=0.8, zorder=0)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.legend(framealpha=0.9)

    plt.tight_layout()
    out = OUTPUT_DIR / "fig7-response-over-time.png"
    plt.savefig(out, bbox_inches="tight")
    plt.close()
    print(f"[OK] 已生成：{out}")


def plot_success_rate(grouped):
    labels = []
    success_rates = []
    sample_counts = []

    for scenario, rows in grouped.items():
        stats = summarize_rows(rows)
        labels.append(scenario)
        success_rates.append(100 - stats["error_rate"])
        sample_counts.append(stats["samples"])

    fig, ax = plt.subplots(figsize=(9, 5))
    fig.patch.set_facecolor(BG_COLOR)
    ax.set_facecolor(BG_COLOR)

    bars = ax.bar(labels, success_rates, color=[COLOR_PRIMARY, COLOR_SECONDARY, COLOR_TERTIARY], width=0.55, zorder=3)
    for bar, count in zip(bars, sample_counts):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.5,
                f"{bar.get_height():.2f}%\n样本 {count}", ha="center", va="bottom", fontsize=9)

    ax.set_ylim(0, max(105, math.ceil(max(success_rates) + 3)))
    ax.set_ylabel("成功率 (%)")
    ax.set_title("各压测场景成功率", fontsize=14, fontweight="bold", pad=12)
    ax.grid(axis="y", color=GRID_COLOR, linewidth=0.8, zorder=0)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)

    plt.tight_layout()
    out = OUTPUT_DIR / "fig7-success-rate.png"
    plt.savefig(out, bbox_inches="tight")
    plt.close()
    print(f"[OK] 已生成：{out}")


def print_summary_table(grouped):
    print("\n" + "=" * 88)
    print("JMeter 压测结果汇总")
    print("=" * 88)
    print(f"{'场景':<12}{'样本数':>10}{'平均(ms)':>12}{'P95(ms)':>12}{'P99(ms)':>12}{'吞吐(req/s)':>14}{'错误率%':>12}")
    print("-" * 88)
    for scenario, rows in grouped.items():
        stats = summarize_rows(rows)
        print(
            f"{scenario:<12}{stats['samples']:>10}"
            f"{stats['avg']:>12.2f}{stats['p95']:>12.2f}{stats['p99']:>12.2f}"
            f"{stats['throughput']:>14.2f}{stats['error_rate']:>12.2f}"
        )
    print("=" * 88)


def main():
    grouped = load_all_data()
    print_summary_table(grouped)
    plot_thread_group_summary(grouped)
    plot_all_api_summary(grouped)
    plot_response_time_over_test(grouped)
    plot_success_rate(grouped)
    print(f"\n图表输出目录：{OUTPUT_DIR}")


if __name__ == "__main__":
    main()
