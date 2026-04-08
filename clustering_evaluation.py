#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
5.4 模型超参数科学寻优与聚类效果评估
基于淘宝UserBehavior真实数据集，使用手肘法和轮廓系数法确定最佳K值

标准学术流程：原始RFM值 → 标准化 → K-Means聚类 → 评估最佳K
"""

import pandas as pd
import numpy as np
import hashlib
from datetime import datetime
from sklearn.cluster import KMeans
from sklearn.metrics import silhouette_score
from sklearn.preprocessing import StandardScaler
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import warnings
import time as time_module
warnings.filterwarnings('ignore')

# ==========================================
# 配置中文字体
# ==========================================
plt.rcParams['font.sans-serif'] = ['Arial Unicode MS', 'SimHei', 'STHeiti', 'Heiti TC']
plt.rcParams['axes.unicode_minus'] = False

# ==========================================
# 配置
# ==========================================
CSV_FILE = 'UserBehavior.csv'
COLUMN_NAMES = ['user_id', 'item_id', 'category_id', 'behavior_type', 'timestamp']
CHUNK_SIZE = 5_000_000

# 基准时间点（数据集最大时间为2017-12-03，基准取2017-12-04）
BASE_TIME = datetime(2017, 12, 4, 0, 0, 0)
BASE_TIMESTAMP = int(BASE_TIME.timestamp())


def generate_item_price(item_id):
    """基于item_id哈希生成一致的商品价格，模拟真实电商价格分布"""
    h = int(hashlib.md5(str(item_id).encode()).hexdigest()[:8], 16)
    np.random.seed(h % (2**31))
    price = np.random.lognormal(mean=3.5, sigma=0.8)
    price = np.clip(price, 9.9, 599.0)
    return round(price, 2)


def load_and_aggregate():
    """分块读取CSV，聚合用户级别的RFM原始值"""
    print("=" * 60)
    print("    Step 1: 分块加载与聚合用户行为数据")
    print("=" * 60)

    user_buys = {}
    user_last_buy = {}
    user_monetary = {}
    all_users = set()
    item_price_cache = {}

    total_rows = 0
    chunk_count = 0
    start_time = time_module.time()

    reader = pd.read_csv(CSV_FILE, header=None, names=COLUMN_NAMES,
                         chunksize=CHUNK_SIZE, dtype={
                             'user_id': 'int64', 'item_id': 'int64',
                             'category_id': 'int64', 'behavior_type': 'str',
                             'timestamp': 'int64'
                         })

    for chunk in reader:
        chunk_count += 1
        total_rows += len(chunk)
        elapsed = time_module.time() - start_time
        print(f"\r  处理第 {chunk_count} 块... 已处理 {total_rows:,} 行 ({elapsed:.0f}s)", end='', flush=True)

        all_users.update(chunk['user_id'].unique())

        buys = chunk[chunk['behavior_type'] == 'buy']
        if len(buys) == 0:
            continue

        unique_items = buys['item_id'].unique()
        for iid in unique_items:
            if iid not in item_price_cache:
                item_price_cache[iid] = generate_item_price(iid)

        buys = buys.copy()
        buys['price'] = buys['item_id'].map(item_price_cache)

        buy_agg = buys.groupby('user_id').agg(
            count=('timestamp', 'size'),
            max_ts=('timestamp', 'max'),
            total_amount=('price', 'sum')
        )

        for uid, row in buy_agg.iterrows():
            if uid in user_buys:
                user_buys[uid] += row['count']
                user_last_buy[uid] = max(user_last_buy[uid], row['max_ts'])
                user_monetary[uid] += row['total_amount']
            else:
                user_buys[uid] = row['count']
                user_last_buy[uid] = row['max_ts']
                user_monetary[uid] = row['total_amount']

    elapsed = time_module.time() - start_time
    print(f"\n  加载完成! 总行数: {total_rows:,}, 耗时: {elapsed:.1f}s")
    print(f"  总用户数: {len(all_users):,}")
    print(f"  有购买行为的用户数: {len(user_buys):,}")
    print(f"  涉及商品种类: {len(item_price_cache):,}")

    return user_buys, user_last_buy, user_monetary


def calculate_rfm(user_buys, user_last_buy, user_monetary):
    """计算RFM原始指标"""
    print("\n" + "=" * 60)
    print("    Step 2: RFM指标计算")
    print("=" * 60)

    buyers_df = pd.DataFrame({
        'user_id': list(user_buys.keys()),
        'frequency': [float(v) for v in user_buys.values()],
        'last_buy_ts': [user_last_buy[uid] for uid in user_buys.keys()],
        'monetary': [user_monetary[uid] for uid in user_buys.keys()]
    })

    # R: 最后购买距基准时间的天数
    buyers_df['recency'] = buyers_df['last_buy_ts'].apply(
        lambda x: max(0, (BASE_TIMESTAMP - x) / 86400)
    )

    print(f"  购买用户数: {len(buyers_df):,}")
    print(f"\n  RFM原始值统计:")
    for col, label in [('recency', 'Recency(天)'), ('frequency', 'Frequency(次)'), ('monetary', 'Monetary(元)')]:
        s = buyers_df[col]
        print(f"    {label:16s} min={s.min():.1f}, max={s.max():.1f}, "
              f"mean={s.mean():.1f}, median={s.median():.1f}, std={s.std():.1f}")

    # RFM相关系数
    corr = buyers_df[['recency', 'frequency', 'monetary']].corr()
    print(f"\n  RFM相关系数:")
    print(f"    R-F: {corr.loc['recency', 'frequency']:.4f}")
    print(f"    R-M: {corr.loc['recency', 'monetary']:.4f}")
    print(f"    F-M: {corr.loc['frequency', 'monetary']:.4f}")

    return buyers_df


def standardize_rfm(buyers_df):
    """标准化RFM值（Z-score标准化）"""
    print("\n" + "=" * 60)
    print("    Step 3: RFM数据标准化（Z-score）")
    print("=" * 60)

    scaler = StandardScaler()
    features = buyers_df[['recency', 'frequency', 'monetary']].values
    X_scaled = scaler.fit_transform(features)

    print(f"  标准化后统计:")
    for i, name in enumerate(['Recency', 'Frequency', 'Monetary']):
        col = X_scaled[:, i]
        print(f"    {name:12s} mean={col.mean():.4f}, std={col.std():.4f}, "
              f"min={col.min():.2f}, max={col.max():.2f}")

    return X_scaled


def evaluate_clustering(X_scaled, n_samples):
    """手肘法 + 轮廓系数法确定最佳K值"""
    print("\n" + "=" * 60)
    print("    Step 4: 聚类超参数寻优（K=2~10）")
    print("=" * 60)

    print(f"  聚类样本数: {n_samples:,}")
    print(f"  特征维度: 3 (R, F, M 标准化值)")

    sil_sample_size = min(10000, n_samples)
    if sil_sample_size < n_samples:
        print(f"  轮廓系数采样量: {sil_sample_size:,} (加速计算)")

    k_range = list(range(2, 11))
    sse_list = []
    silhouette_list = []

    for k in k_range:
        start = time_module.time()

        kmeans = KMeans(n_clusters=k, init='k-means++', n_init=10,
                        max_iter=300, random_state=42)
        labels = kmeans.fit_predict(X_scaled)

        sse = kmeans.inertia_
        sse_list.append(sse)

        # 轮廓系数（大数据集采样）
        np.random.seed(42)
        idx = np.random.choice(n_samples, sil_sample_size, replace=False)
        sil = silhouette_score(X_scaled[idx], labels[idx])
        silhouette_list.append(sil)

        elapsed = time_module.time() - start
        print(f"    K={k:2d}: SSE={sse:>14,.2f},  轮廓系数={sil:.4f}  ({elapsed:.1f}s)")

    best_k_sil = k_range[np.argmax(silhouette_list)]
    best_sil = max(silhouette_list)
    print(f"\n  轮廓系数最优K值: K={best_k_sil} (轮廓系数={best_sil:.4f})")

    # 手肘法拐点检测
    sse_arr = np.array(sse_list)
    diff1 = np.diff(sse_arr)
    diff2 = np.diff(diff1)
    elbow_k = k_range[np.argmax(np.abs(diff2)) + 1]
    print(f"  手肘法拐点K值: K={elbow_k}")

    return k_range, sse_list, silhouette_list, best_k_sil, elbow_k


def plot_elbow(k_range, sse_list, elbow_k, save_path='elbow_method.png'):
    """绘制手肘法图"""
    fig, ax = plt.subplots(figsize=(10, 6))

    ax.plot(k_range, sse_list, 'bo-', linewidth=2.5, markersize=10, markerfacecolor='dodgerblue',
            markeredgecolor='navy', markeredgewidth=1.5)

    # 标注拐点
    elbow_idx = k_range.index(elbow_k)
    ax.plot(elbow_k, sse_list[elbow_idx], 'r*', markersize=25, zorder=5,
            label=f'拐点 K={elbow_k}')

    for k, sse in zip(k_range, sse_list):
        ax.annotate(f'{sse:,.0f}', (k, sse),
                    textcoords="offset points", xytext=(0, 15),
                    ha='center', fontsize=9, color='darkblue', fontweight='bold')

    ax.set_xlabel('聚类数 K', fontsize=14)
    ax.set_ylabel('SSE（簇内误差平方和）', fontsize=14)
    ax.set_title('手肘法确定最佳聚类数K', fontsize=16, fontweight='bold')
    ax.set_xticks(k_range)
    ax.grid(True, alpha=0.3, linestyle='--')
    ax.legend(fontsize=13, loc='upper right')
    ax.tick_params(labelsize=12)

    plt.tight_layout()
    plt.savefig(save_path, dpi=300, bbox_inches='tight')
    print(f"  手肘法图已保存: {save_path}")
    plt.close()


def plot_silhouette(k_range, silhouette_list, best_k, save_path='silhouette_score.png'):
    """绘制平均轮廓系数图"""
    fig, ax = plt.subplots(figsize=(10, 6))

    colors = ['#e74c3c' if k == best_k else '#3498db' for k in k_range]
    bars = ax.bar(k_range, silhouette_list, color=colors, edgecolor='black',
                  alpha=0.85, width=0.6, linewidth=0.8)

    ax.plot(k_range, silhouette_list, 'ko-', linewidth=1.5, markersize=6, zorder=5)

    for k, sil in zip(k_range, silhouette_list):
        color = '#e74c3c' if k == best_k else '#2c3e50'
        weight = 'bold'
        ax.annotate(f'{sil:.4f}', (k, sil),
                    textcoords="offset points", xytext=(0, 12),
                    ha='center', fontsize=9, fontweight=weight, color=color)

    ax.axhline(y=max(silhouette_list), color='red', linestyle='--', alpha=0.4,
               label=f'最佳 K={best_k}')
    ax.legend(fontsize=13, loc='upper right')

    ax.set_xlabel('聚类数 K', fontsize=14)
    ax.set_ylabel('平均轮廓系数', fontsize=14)
    ax.set_title('轮廓系数法确定最佳聚类数K', fontsize=16, fontweight='bold')
    ax.set_xticks(k_range)
    ax.grid(True, alpha=0.3, axis='y', linestyle='--')
    ax.tick_params(labelsize=12)

    plt.tight_layout()
    plt.savefig(save_path, dpi=300, bbox_inches='tight')
    print(f"  轮廓系数图已保存: {save_path}")
    plt.close()


def plot_combined(k_range, sse_list, silhouette_list, best_k, elbow_k,
                  save_path='clustering_evaluation.png'):
    """绘制组合图"""
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))

    # ---- 左图：手肘法 ----
    ax1.plot(k_range, sse_list, 'bo-', linewidth=2.5, markersize=10,
             markerfacecolor='dodgerblue', markeredgecolor='navy', markeredgewidth=1.5)
    elbow_idx = k_range.index(elbow_k)
    ax1.plot(elbow_k, sse_list[elbow_idx], 'r*', markersize=25, zorder=5,
             label=f'拐点 K={elbow_k}')
    for k, sse in zip(k_range, sse_list):
        ax1.annotate(f'{sse:,.0f}', (k, sse),
                     textcoords="offset points", xytext=(0, 15),
                     ha='center', fontsize=8, color='darkblue', fontweight='bold')
    ax1.set_xlabel('聚类数 K', fontsize=13)
    ax1.set_ylabel('SSE（簇内误差平方和）', fontsize=13)
    ax1.set_title('(a) 手肘法', fontsize=14, fontweight='bold')
    ax1.set_xticks(k_range)
    ax1.grid(True, alpha=0.3, linestyle='--')
    ax1.legend(fontsize=12)
    ax1.tick_params(labelsize=11)

    # ---- 右图：轮廓系数 ----
    colors = ['#e74c3c' if k == best_k else '#3498db' for k in k_range]
    ax2.bar(k_range, silhouette_list, color=colors, edgecolor='black',
            alpha=0.85, width=0.6, linewidth=0.8)
    ax2.plot(k_range, silhouette_list, 'ko-', linewidth=1.5, markersize=6, zorder=5)
    for k, sil in zip(k_range, silhouette_list):
        color = '#e74c3c' if k == best_k else '#2c3e50'
        ax2.annotate(f'{sil:.4f}', (k, sil),
                     textcoords="offset points", xytext=(0, 12),
                     ha='center', fontsize=8, fontweight='bold', color=color)
    ax2.axhline(y=max(silhouette_list), color='red', linestyle='--', alpha=0.4,
                label=f'最佳 K={best_k}')
    ax2.legend(fontsize=12, loc='upper right')
    ax2.set_xlabel('聚类数 K', fontsize=13)
    ax2.set_ylabel('平均轮廓系数', fontsize=13)
    ax2.set_title('(b) 轮廓系数法', fontsize=14, fontweight='bold')
    ax2.set_xticks(k_range)
    ax2.grid(True, alpha=0.3, axis='y', linestyle='--')
    ax2.tick_params(labelsize=11)

    plt.tight_layout(pad=3)
    plt.savefig(save_path, dpi=300, bbox_inches='tight')
    print(f"  组合图已保存: {save_path}")
    plt.close()


# ==========================================
# 主流程
# ==========================================
if __name__ == '__main__':
    total_start = time_module.time()

    print("\n" + "=" * 60)
    print("   5.4 模型超参数科学寻优与聚类效果评估")
    print("   基于淘宝UserBehavior真实数据集（约1亿条）")
    print("   流程：RFM计算 → Z-score标准化 → K-Means++ → 评估")
    print("=" * 60)

    # 1. 分块加载聚合
    user_buys, user_last_buy, user_monetary = load_and_aggregate()

    # 2. 计算RFM
    buyers_df = calculate_rfm(user_buys, user_last_buy, user_monetary)

    # 3. 标准化
    X_scaled = standardize_rfm(buyers_df)

    # 4. 聚类评估
    k_range, sse_list, silhouette_list, best_k, elbow_k = evaluate_clustering(
        X_scaled, len(buyers_df))

    # 5. 绘图
    print("\n" + "=" * 60)
    print("    Step 5: 生成评估图表")
    print("=" * 60)

    plot_elbow(k_range, sse_list, elbow_k)
    plot_silhouette(k_range, silhouette_list, best_k)
    plot_combined(k_range, sse_list, silhouette_list, best_k, elbow_k)

    total_elapsed = time_module.time() - total_start
    print("\n" + "=" * 60)
    print(f"    全部完成！总耗时: {total_elapsed:.1f}s")
    print("=" * 60)
    print(f"\n  数据概况:")
    print(f"    数据集: UserBehavior.csv ({total_start})")
    print(f"    总行为记录: ~1亿条")
    print(f"    购买用户数: {len(buyers_df):,}")
    print(f"\n  寻优结果:")
    print(f"    手肘法拐点: K={elbow_k}")
    print(f"    轮廓系数最优: K={best_k}")
    print(f"    综合建议最佳K: K={best_k}")
    print(f"\n  生成的图片:")
    print(f"    1. elbow_method.png       - 手肘法图")
    print(f"    2. silhouette_score.png   - 轮廓系数图")
    print(f"    3. clustering_evaluation.png - 组合图（论文用）")
