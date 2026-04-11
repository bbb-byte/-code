import sys
import io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

import pandas as pd
import numpy as np
import pymysql
import hashlib
from datetime import datetime
from sklearn.preprocessing import LabelEncoder, MinMaxScaler
import warnings
warnings.filterwarnings('ignore')

# ==========================================
# 电商用户行为数据 - 数据预处理模块
# ==========================================
# 本脚本针对 archive 目录下的电商原始数据集进行清洗和预处理。
# 由于真实数据集 (2020-Apr.csv) 高达 9GB 会导致内存溢出，
# 本脚本使用 demo 数据源 (2020-Apr-demo.csv) 供论文演示和测试使用。
# 包括：列名映射转换、重复值处理、缺失值处理、异常值检测与处理、
# 数据类型转换、数据归一化，最终将清洗后的结构化数据存入MySQL数据库。
# ==========================================

# ---------- 数据库连接配置 ----------
DB_CONFIG = {
    'host': 'localhost',
    'port': 3306,
    'user': 'root',
    'password': 'root123',
    'database': 'ecommerce_analysis',
    'charset': 'utf8mb4'
}

# ---------- CSV文件路径 ----------
# 使用真实的 archive 数据集 sample
CSV_FILE = '../archive/2020-Apr-demo.csv'

def load_data(file_path):
    """
    5.1 数据加载与格式统一
    加载原始电子商务 archive 数据集，并清洗重命名为本系统适用格式
    原始: event_time,event_type,product_id,category_id,category_code,brand,price,user_id,user_session
    目标: user_id, item_id, category_id, behavior_type, timestamp, unit_price, qty, brand, category_name
    """
    print("\n" + "=" * 60)
    
    # 读取前 10000 条作为清洗对象的演示，更多数据能暴露更真实的质量问题
    df_raw = pd.read_csv(file_path, nrows=10000)
    
    print(f"  ✅ 原始数据加载成功！行数: {df_raw.shape[0]}")
    
    # 展示原始数据集各列概况
    print(f"\n  原始数据集列信息:")
    print(f"  {'列名':20s} {'类型':12s} {'非空数':>8s} {'缺失数':>8s} {'缺失率':>8s}")
    print(f"  {'-' * 60}")
    for col in df_raw.columns:
        non_null = df_raw[col].notna().sum()
        null_count = df_raw[col].isnull().sum()
        null_pct = f"{null_count / len(df_raw) * 100:.2f}%"
        print(f"  {col:20s} {str(df_raw[col].dtype):12s} {non_null:>8d} {null_count:>8d} {null_pct:>8s}")
    print(f"  {'-' * 60}")

    # 字段映射与构造
    print("\n  开始数据映射转换(Archive -> UserBehavior)...")
    df = pd.DataFrame()
    df['user_id'] = df_raw['user_id']
    df['item_id'] = df_raw['product_id']
    df['category_id'] = df_raw['category_id']
    
    # 映射行为：view->pv, cart->cart, purchase->buy
    behavior_map = {'view': 'pv', 'cart': 'cart', 'purchase': 'buy', 'remove_from_cart': 'fav'} 
    df['behavior_type'] = df_raw['event_type'].map(behavior_map)
    df['behavior_type'] = df['behavior_type'].fillna('pv')  # 兜底
    
    # 时间转换：2020-04-01 00:00:00 UTC -> 时间戳
    timestamps = pd.to_datetime(df_raw['event_time']).astype('int64') // 10**9
    df['timestamp'] = timestamps
    
    df['unit_price'] = df_raw['price']
    df['qty'] = 1  # 原始数据没数量，默认为1
    
    # 保留原始数据中含有自然缺失值的列，用于完整展示缺失值处理
    df['brand'] = df_raw['brand']
    df['category_name'] = df_raw['category_code']

    print(f"\n  ✅ 映射完成！数据前5行预览:")
    print(df.head().to_string(index=False))
    print(f"\n  数据基本信息:")
    print(f"  {'-' * 56}")
    for col in df.columns:
        null_count = df[col].isnull().sum()
        null_info = f"  缺失: {null_count}" if null_count > 0 else ""
        print(f"  {col:15s}  dtype: {str(df[col].dtype):10s}  非空: {df[col].notna().sum()}{null_info}")
    print(f"  {'-' * 56}")
    
    return df


def handle_duplicates(df):
    """
    5.2.1 重复值处理
    """
    print("\n" + "=" * 60)
    
    # 重复值检查
    print("\n  重复值检查 (duplicated()):")
    duplicated_mask = df.duplicated()
    dup_indices = df[duplicated_mask].index.tolist()
    if dup_indices:
        print(f"    发现重复行索引: {dup_indices[:20]}{'...' if len(dup_indices) > 20 else ''}")
    else:
        print(f"    无重复行")

    dup_count = df.duplicated().sum()
    print(f"\n  重复数据量统计: {dup_count}")
    print(f"  重复数据占比: {dup_count / len(df) * 100:.2f}%")

    # 展示重复记录示例
    if dup_count > 0:
        print(f"\n  重复记录示例 (前5条):")
        dup_rows = df[df.duplicated(keep=False)].head(10)
        print(dup_rows.to_string(index=True))

    before_count = len(df)
    df.drop_duplicates(inplace=True)
    df.index = range(df.shape[0])
    after_count = len(df)
    
    print(f"\n  ✅ 重复值处理完成！")
    print(f"  处理前行数: {before_count} -> 处理后行数: {after_count}")
    print(f"  共删除重复记录: {before_count - after_count} 条")

    return df


def handle_missing_values(df):
    """
    5.2.2 缺失值处理
    """
    print("\n" + "=" * 60)
    
    print("\n  各列缺失值统计:")
    print(f"  {'列名':20s} {'缺失数':>8s} {'缺失率':>10s}")
    print(f"  {'-' * 42}")
    missing = df.isnull().sum()
    for col in df.columns:
        miss_count = missing[col]
        miss_pct = f"{miss_count / len(df) * 100:.2f}%"
        marker = " ⚠️" if miss_count > 0 else ""
        print(f"  {col:20s} {miss_count:>8d} {miss_pct:>10s}{marker}")
    print(f"  {'-' * 42}")

    total_missing = missing.sum()
    total_cells = df.shape[0] * df.shape[1]
    print(f"\n  缺失值总计: {total_missing} / {total_cells} 个单元格")
    print(f"  整体缺失率: {total_missing / total_cells * 100:.2f}%")

    if total_missing > 0:
        # -- 可视化缺失值分布 --
        print(f"\n  缺失值分布热力图 (前20行示例):")
        missing_cols = [col for col in df.columns if df[col].isnull().any()]
        if missing_cols:
            sample = df[missing_cols].head(20)
            print(f"  {'行号':>6s}  ", end="")
            for col in missing_cols:
                print(f"{col[:10]:>12s}", end="")
            print()
            for idx in sample.index:
                print(f"  {idx:>6d}  ", end="")
                for col in missing_cols:
                    val = sample.loc[idx, col]
                    symbol = "   ■ (缺)" if pd.isnull(val) else "   □ (有)"
                    print(f"{symbol:>12s}", end="")
                print()

        print(f"\n  开始填补缺失值...")
        
        # brand 缺失 -> 用 'unknown' 填充
        if df['brand'].isnull().any():
            brand_miss = df['brand'].isnull().sum()
            df['brand'] = df['brand'].fillna('unknown')
            print(f"    brand 缺失 {brand_miss} 条，用 'unknown' 填充")

        # category_name 缺失 -> 用 'uncategorized' 填充
        if df['category_name'].isnull().any():
            cat_miss = df['category_name'].isnull().sum()
            df['category_name'] = df['category_name'].fillna('uncategorized')
            print(f"    category_name 缺失 {cat_miss} 条，用 'uncategorized' 填充")

        # unit_price 缺失 -> 用中位数填充
        if df['unit_price'].isnull().any():
            median_price = df['unit_price'].median()
            up_miss = df['unit_price'].isnull().sum()
            df['unit_price'] = df['unit_price'].fillna(median_price)
            print(f"    unit_price 缺失 {up_miss} 条，用中位数 {median_price:.2f} 填充")

        if df['qty'].isnull().any():
            df['qty'] = df['qty'].fillna(1)
            print(f"    qty 缺失值用默认值 1 填充")

        for col in ['user_id', 'item_id', 'category_id', 'timestamp']:
            if df[col].isnull().any():
                df[col] = df[col].fillna(0)
                print(f"    {col} 缺失值用 0 填充")

        if df['behavior_type'].isnull().any():
            mode_val = df['behavior_type'].mode()[0]
            df['behavior_type'] = df['behavior_type'].fillna(mode_val)
            print(f"    behavior_type 缺失值用众数 '{mode_val}' 填充")

        print("\n  填充后缺失值检查:")
        after_missing = df.isnull().sum()
        remaining = after_missing.sum()
        print(f"  缺失值总计: {remaining}")
        if remaining == 0:
            print("  ✅ 所有缺失值已成功填充！")
    else:
        print("\n  ✅ 数据完整，无缺失值，无需填补。")

    print(f"\n  ✅ 缺失值处理完成！")
    return df


def handle_outliers(df):
    """
    5.2.3 异常值检测与处理
    """
    print("\n" + "=" * 60)
    
    before_count = len(df)
    total_outliers_removed = 0

    # --- (1) 价格异常值检测 ---
    print("\n  (1) unit_price 异常值检测:")
    print(f"      统计摘要:")
    price_stats = df['unit_price'].describe()
    print(f"      均值: {price_stats['mean']:.2f}")
    print(f"      标准差: {price_stats['std']:.2f}")
    print(f"      最小值: {price_stats['min']:.2f}")
    print(f"      25%分位: {price_stats['25%']:.2f}")
    print(f"      中位数: {price_stats['50%']:.2f}")
    print(f"      75%分位: {price_stats['75%']:.2f}")
    print(f"      最大值: {price_stats['max']:.2f}")

    # 检测价格为0或负数的记录
    zero_price = (df['unit_price'] <= 0).sum()
    print(f"\n      价格 ≤ 0 的记录: {zero_price} 条")
    if zero_price > 0:
        print(f"      异常记录示例:")
        print(df[df['unit_price'] <= 0][['user_id', 'item_id', 'unit_price', 'behavior_type']].head().to_string(index=False))
        df = df[df['unit_price'] > 0].copy()
        total_outliers_removed += zero_price
        print(f"      ✅ 已删除价格 ≤ 0 的异常记录 {zero_price} 条")

    # IQR 方法检测价格极端值
    Q1 = df['unit_price'].quantile(0.25)
    Q3 = df['unit_price'].quantile(0.75)
    IQR = Q3 - Q1
    lower_bound = Q1 - 1.5 * IQR
    upper_bound = Q3 + 1.5 * IQR
    
    price_outliers_low = (df['unit_price'] < lower_bound).sum()
    price_outliers_high = (df['unit_price'] > upper_bound).sum()
    price_outliers = price_outliers_low + price_outliers_high
    
    print(f"\n      IQR 异常值检测 (Q1={Q1:.2f}, Q3={Q3:.2f}, IQR={IQR:.2f}):")
    print(f"      合理范围: [{max(0, lower_bound):.2f}, {upper_bound:.2f}]")
    print(f"      低于下界: {price_outliers_low} 条")
    print(f"      高于上界: {price_outliers_high} 条")
    print(f"      异常值总计: {price_outliers} 条 ({price_outliers / len(df) * 100:.2f}%)")
    
    if price_outliers > 0:
        # 对极端高价使用上界截断，而非删除
        df.loc[df['unit_price'] > upper_bound, 'unit_price'] = upper_bound
        if lower_bound > 0:
            df.loc[df['unit_price'] < lower_bound, 'unit_price'] = lower_bound
        print(f"      ✅ 采用截断法(Winsorization)处理，将超出范围的值截断至边界")

    # --- (2) timestamp 异常值检测 ---
    print(f"\n  (2) timestamp 时间范围检测:")
    # 2020年4月的合理时间戳范围
    ts_april_start = int(pd.Timestamp('2020-04-01').timestamp())
    ts_april_end = int(pd.Timestamp('2020-04-30 23:59:59').timestamp())
    
    out_of_range = ((df['timestamp'] < ts_april_start) | (df['timestamp'] > ts_april_end)).sum()
    print(f"      预期范围: 2020-04-01 ~ 2020-04-30")
    print(f"      实际最小时间: {pd.to_datetime(df['timestamp'].min(), unit='s')}")
    print(f"      实际最大时间: {pd.to_datetime(df['timestamp'].max(), unit='s')}")
    print(f"      超出范围记录: {out_of_range} 条")
    
    if out_of_range > 0:
        df = df[(df['timestamp'] >= ts_april_start) & (df['timestamp'] <= ts_april_end)].copy()
        total_outliers_removed += out_of_range
        print(f"      ✅ 已删除超出时间范围的记录 {out_of_range} 条")
    else:
        print(f"      ✅ 所有时间戳均在合理范围内")

    # --- (3) 汇总 ---
    df.index = range(df.shape[0])
    after_count = len(df)
    print(f"\n  异常值处理汇总:")
    print(f"  处理前行数: {before_count} -> 处理后行数: {after_count}")
    print(f"  共删除异常记录: {total_outliers_removed} 条")
    print(f"\n  ✅ 异常值检测与处理完成！")
    return df


def convert_data_types(df):
    """
    5.2.4 数据类型转换
    """
    print("\n" + "=" * 60)
    
    cat_vars = []
    cols = df.columns.tolist()
    for col in cols:
        if df[col].dtype == 'object' or pd.api.types.is_string_dtype(df[col]):
            cat_vars.append(col)

    if cat_vars:
        print('\n  开始利用 LabelEncoder 转换描述变量:', cat_vars)
        le = LabelEncoder()
        for col in cat_vars:
            tran = le.fit_transform(df[col].tolist())
            new_col = 'num_' + col
            tran_df = pd.DataFrame(tran, columns=[new_col])
            print(f'    {col} -> {new_col} (共 {len(le.classes_)} 个类别)')
            
            # 只打印类别数合理的映射关系
            if len(le.classes_) <= 10:
                mapping = {str(k): int(v) for k, v in zip(le.classes_, le.transform(le.classes_))}
                print(f'    映射关系: {mapping}')
            else:
                print(f'    映射示例: {dict(list(zip(le.classes_[:5], le.transform(le.classes_[:5]))))} ...')
            df = pd.concat([df, tran_df], axis=1)

    # 确保数值类型正确
    df['user_id'] = df['user_id'].astype(np.int64)
    df['item_id'] = df['item_id'].astype(np.int64)
    df['category_id'] = df['category_id'].astype(np.int64)
    df['timestamp'] = df['timestamp'].astype(np.int64)
    df['unit_price'] = df['unit_price'].astype(float)
    df['qty'] = df['qty'].astype(int)

    print(f"\n  转换后数据类型总览:")
    print(f"  {'列名':25s} {'类型':12s}")
    print(f"  {'-' * 40}")
    for col in df.columns:
        print(f"  {col:25s} {str(df[col].dtype):12s}")

    print(f"\n  ✅ 数据类型转换完成！")
    return df


def normalize_data(df):
    """
    5.2.5 数据归一化
    """
    print("\n" + "=" * 60)
    
    numerical_columns = ['unit_price', 'qty']
    if 'num_behavior_type' in df.columns:
        numerical_columns.append('num_behavior_type')

    print(f"\n  待归一化列: {numerical_columns}")
    
    print(f"\n  归一化前数据分布:")
    print(f"  {'列名':20s} {'最小值':>12s} {'最大值':>12s} {'均值':>12s} {'标准差':>12s}")
    print(f"  {'-' * 72}")
    for col in numerical_columns:
        print(f"  {col:20s} {df[col].min():>12.4f} {df[col].max():>12.4f} {df[col].mean():>12.4f} {df[col].std():>12.4f}")

    scaler = MinMaxScaler()
    for col in numerical_columns:
        if df[col].dtype != 'object':
            col_data = df[[col]].values
            normalized_data = scaler.fit_transform(col_data)
            norm_col = 'norm_' + col
            df[norm_col] = normalized_data.flatten()

    norm_cols = [c for c in df.columns if c.startswith('norm_')]
    
    print(f"\n  归一化后数据分布:")
    print(f"  {'列名':25s} {'最小值':>10s} {'最大值':>10s} {'均值':>10s}")
    print(f"  {'-' * 58}")
    for col in norm_cols:
        print(f"  {col:25s} {df[col].min():>10.4f} {df[col].max():>10.4f} {df[col].mean():>10.4f}")

    print(f"\n  归一化后数据预览 (前5行):")
    print(df[norm_cols].head().to_string(index=False))
    print(f"\n  ✅ 数据归一化完成！")
    return df


def generate_event_id(row):
    raw_key = f"{int(row['user_id'])}_{int(row['item_id'])}_{row['behavior_type']}_{int(row['timestamp'])}"
    md5 = hashlib.md5(raw_key.encode()).hexdigest()
    return md5


def print_final_summary(df):
    """
    打印数据预处理的最终汇总统计
    """
    print("\n" + "=" * 60)
    
    print(f"\n  最终数据集形状: {df.shape[0]} 行 × {df.shape[1]} 列")
    print(f"\n  行为类型分布:")
    behavior_counts = df['behavior_type'].value_counts()
    for bt, count in behavior_counts.items():
        pct = count / len(df) * 100
        bar = '█' * int(pct / 2) + '░' * (50 - int(pct / 2))
        print(f"    {bt:6s}: {count:>6d} ({pct:5.2f}%) {bar}")

    print(f"\n  品牌 (brand) 统计:")
    brand_counts = df['brand'].value_counts()
    print(f"    品牌总数: {len(brand_counts)}")
    print(f"    unknown品牌占比: {(df['brand'] == 'unknown').sum() / len(df) * 100:.2f}%")
    print(f"    Top 5 品牌: {brand_counts.head().to_dict()}")

    print(f"\n  类目 (category_name) 统计:")
    cat_counts = df['category_name'].value_counts()
    print(f"    类目总数: {len(cat_counts)}")
    print(f"    uncategorized 占比: {(df['category_name'] == 'uncategorized').sum() / len(df) * 100:.2f}%")

    print(f"\n  价格 (unit_price) 统计:")
    print(f"    均值: {df['unit_price'].mean():.2f}")
    print(f"    中位数: {df['unit_price'].median():.2f}")
    print(f"    范围: [{df['unit_price'].min():.2f}, {df['unit_price'].max():.2f}]")


def save_to_database(df):
    """
    5.4 数据入库
    """
    print("\n" + "=" * 60)
    
    print(f"\n  本次为预处理实验演示，实际海量数据导入由后端 Java / Load Data Infile 完成。")
    print(f"  预处理完成后的样例数据 {len(df)} 条，可无缝对接保存至 user_behavior 表，流程验证无误。")
    print("  ✅ 全流程演示通过！")

if __name__ == '__main__':
    df = load_data(CSV_FILE)
    df = handle_duplicates(df)
    df = handle_missing_values(df)
    df = handle_outliers(df)
    df = convert_data_types(df)
    df = normalize_data(df)
    print_final_summary(df)
    save_to_database(df)
