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
# 本脚本对爬虫采集的原始数据进行清洗和预处理，
# 包括：重复值处理、缺失值处理、数据类型转换、
# 数据归一化，最终将清洗后的数据存入MySQL数据库。
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
CSV_FILE = '../crawled_user_behavior.csv'

# CSV列名定义
COLUMN_NAMES = ['user_id', 'item_id', 'category_id', 'behavior_type', 'timestamp', 'unit_price', 'qty']


def load_data(file_path):
    """
    5.1 数据加载
    加载爬虫采集的CSV原始数据文件
    """
    print("=" * 60)
    print("           数据预处理流程开始")
    print("=" * 60)

    print("\n【5.1 数据加载】")
    print(f"  正在读取数据文件: {file_path}")

    df = pd.read_csv(file_path, header=None, names=COLUMN_NAMES)

    print(f"  ✅ 数据加载成功！")
    print(f"  原始数据行数: {df.shape[0]}")
    print(f"  数据列数: {df.shape[1]}")
    print(f"\n  数据前5行预览:")
    print(df.head().to_string(index=False))
    print(f"\n  数据基本信息:")
    print(f"  {'-' * 40}")
    for col in df.columns:
        print(f"  {col:15s}  dtype: {str(df[col].dtype):10s}  非空: {df[col].notna().sum()}")
    print(f"  {'-' * 40}")

    return df


def handle_duplicates(df):
    """
    5.2.1 重复值处理
    使用 duplicated() 方法检测数据集中存在的重复部分，
    使用 drop_duplicates() 删除重复数据，增强数据质量。
    """
    print("\n" + "=" * 60)
    print("【5.2.1 重复值处理】")
    print("=" * 60)

    # 重复值检查 duplicated() 返回布尔型数据，告知重复值的位置
    print("\n  重复值检查 (duplicated()):")
    duplicated_mask = df.duplicated()
    print(f"  重复值位置（前10条）:")
    dup_indices = df[duplicated_mask].index.tolist()
    if dup_indices:
        print(f"    行号: {dup_indices[:10]}{'...' if len(dup_indices) > 10 else ''}")
    else:
        print(f"    无重复行")

    # 重复值数据的个数
    dup_count = df.duplicated().sum()
    print(f"\n  重复数据量统计: {dup_count}")
    print(f"  重复数据占比: {dup_count / len(df) * 100:.2f}%")

    # 删除重复值数据【inplace=True表示直接在原始数据上进行删除操作】
    before_count = len(df)
    df.drop_duplicates(inplace=True)

    # 修改原始数据后可能需要重置index下标排序以便后续针对特殊数据定位
    df.index = range(df.shape[0])

    after_count = len(df)
    print(f"\n  ✅ 重复值处理完成！")
    print(f"  处理前行数: {before_count}")
    print(f"  处理后行数: {after_count}")
    print(f"  删除重复行: {before_count - after_count}")

    return df


def handle_missing_values(df):
    """
    5.2.2 缺失值处理
    空值的地方丢失了大量的有用信息，直接降低数据质量，
    低质量数据导致模型效果无法满足目标。
    利用缺失值填充技术，可以一定程度复原真实数据。
    使用 data.isnull().sum() 来检查缺失值，fillna() 进行缺失值填补。
    """
    print("\n" + "=" * 60)
    print("【5.2.2 缺失值处理】")
    print("=" * 60)

    # 查找是否存在缺失值，并统计缺失数量
    print("\n  缺失值数据量:")
    missing = df.isnull().sum()
    print(missing.to_string())

    total_missing = missing.sum()
    print(f"\n  缺失值总计: {total_missing}")

    if total_missing > 0:
        print("\n  开始填补缺失值...")

        # ① 利用数据内部的关联直接替代缺失数据
        # 数值型字段：用中位数填充（对异常值不敏感）
        if df['unit_price'].isnull().any():
            median_price = df['unit_price'].median()
            df['unit_price'].fillna(median_price, inplace=True)
            print(f"    unit_price 缺失值用中位数 {median_price} 填充")

        if df['qty'].isnull().any():
            df['qty'].fillna(1, inplace=True)
            print(f"    qty 缺失值用默认值 1 填充")

        # 整型字段：用 0 填充
        for col in ['user_id', 'item_id', 'category_id', 'timestamp']:
            if df[col].isnull().any():
                df[col].fillna(0, inplace=True)
                print(f"    {col} 缺失值用 0 填充")

        # 字符串字段：用众数填充
        if df['behavior_type'].isnull().any():
            mode_val = df['behavior_type'].mode()[0]
            df['behavior_type'].fillna(mode_val, inplace=True)
            print(f"    behavior_type 缺失值用众数 '{mode_val}' 填充")

        # 验证填充结果
        print("\n  填充后缺失值检查:")
        print(df.isnull().sum().to_string())
    else:
        print("\n  ✅ 数据完整，无缺失值，无需填补。")

    print(f"\n  ✅ 缺失值处理完成！当前数据行数: {len(df)}")
    return df


def convert_data_types(df):
    """
    5.2.3 数据类型转换
    算法要求输入的数据必须是数字，不能是字符串，
    这就要求将数据中的描述性变量转换为数值型数据。
    使用 LabelEncoder 将分类变量编码为数值。
    """
    print("\n" + "=" * 60)
    print("【5.2.3 数据类型转换】")
    print("=" * 60)

    # 寻找描述变量，并将其存储到cat_vars这个list中去
    cat_vars = []
    print('\n  描述变量有:')
    cols = df.columns.tolist()
    for col in cols:
        if df[col].dtype == 'object' or pd.api.types.is_string_dtype(df[col]):
            print(f'    {col}')
            cat_vars.append(col)

    if not cat_vars:
        print('    无描述变量，跳过类型转换。')
    else:
        # 将行为类型(behavior_type)转换为数值编码
        print('\n  开始转换描述变量...')
        le = LabelEncoder()

        for col in cat_vars:
            # 将描述变量自动转换为数值型变量，并将转换后的数据附加到原始数据上
            tran = le.fit_transform(df[col].tolist())
            new_col = 'num_' + col
            tran_df = pd.DataFrame(tran, columns=[new_col])
            print(f'    {col} 经过转化为 {new_col}')

            # 显示映射关系
            mapping = {str(k): int(v) for k, v in zip(le.classes_, le.transform(le.classes_))}
            print(f'    映射关系: {mapping}')

            df = pd.concat([df, tran_df], axis=1)
            # 保留原始列用于后续入库，不删除

    # 确保数值类型正确
    df['user_id'] = df['user_id'].astype(np.int64)
    df['item_id'] = df['item_id'].astype(np.int64)
    df['category_id'] = df['category_id'].astype(np.int64)
    df['timestamp'] = df['timestamp'].astype(np.int64)
    df['unit_price'] = df['unit_price'].astype(float)
    df['qty'] = df['qty'].astype(int)

    print(f'\n  转换后数据类型:')
    for col in df.columns:
        print(f'    {col:20s} -> {str(df[col].dtype)}')

    print(f"\n  ✅ 数据类型转换完成！")
    return df


def normalize_data(df):
    """
    5.2.4 数据归一化
    在对数据进行分析与建模的过程中，经常会遇到不同的评价指标，
    这些指标往往会有着不同的度量单位和取值范围，这种差异可能会引入误差。
    为了减少误差所带来的影响，使用 fit_transform() 方法对特定列进行归一化处理。
    """
    print("\n" + "=" * 60)
    print("【5.2.4 数据归一化】")
    print("=" * 60)

    # 需要归一化的数值列
    numerical_columns = ['unit_price', 'qty']

    # 额外的数值编码列
    if 'num_behavior_type' in df.columns:
        numerical_columns.append('num_behavior_type')

    print(f"\n  待归一化列: {numerical_columns}")
    print(f"\n  归一化前数据统计:")
    print(df[numerical_columns].describe().to_string())

    # 进行规范化
    scaler = MinMaxScaler()

    # 创建归一化后的列（保留原始列用于入库）
    for col in numerical_columns:
        if df[col].dtype != 'object':
            # 从 DataFrame 中提取出该列，并将其转换为二维数组
            col_data = df[[col]].values
            # 调用 MinMaxScaler 对象的 fit_transform() 方法进行规范化
            normalized_data = scaler.fit_transform(col_data)
            # 将规范化后的数组转换回一维，并将其添加回 DataFrame 中
            norm_col = 'norm_' + col
            df[norm_col] = normalized_data.flatten()

    # 数值型数据标准化
    norm_cols = [c for c in df.columns if c.startswith('norm_')]
    print(f"\n  归一化后数据预览:")
    print(df[norm_cols].head().to_string(index=False))

    print(f"\n  ✅ 数据归一化完成！")
    return df


def generate_event_id(row):
    """生成事件唯一ID（与Java后端保持一致）"""
    raw_key = f"{int(row['user_id'])}_{int(row['item_id'])}_{row['behavior_type']}_{int(row['timestamp'])}"
    md5 = hashlib.md5(raw_key.encode()).hexdigest()
    return md5


def save_to_database(df):
    """
    5.3 数据入库
    经过预处理后的数据文件用 pymysql 链接数据库并且放入数据库中。
    """
    print("\n" + "=" * 60)
    print("【5.3 数据入库】")
    print("=" * 60)

    print(f"\n  正在连接MySQL数据库...")
    print(f"  服务器: {DB_CONFIG['host']}:{DB_CONFIG['port']}")
    print(f"  数据库: {DB_CONFIG['database']}")

    try:
        conn = pymysql.connect(**DB_CONFIG)
        cursor = conn.cursor()
        print("  ✅ 数据库连接成功！")

        # 准备插入数据（使用原始列，非归一化列）
        insert_sql = """
            INSERT INTO user_behavior 
            (event_id, user_id, item_id, category_id, behavior_type, 
             behavior_time, behavior_date_time, unit_price, qty)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON DUPLICATE KEY UPDATE 
                unit_price = VALUES(unit_price),
                qty = VALUES(qty)
        """

        success_count = 0
        error_count = 0

        print(f"\n  开始写入 {len(df)} 条数据...")

        for idx, row in df.iterrows():
            try:
                # 生成事件ID（与Java后端逻辑一致）
                event_id = generate_event_id(row)

                # 时间戳转日期
                ts = int(row['timestamp'])
                behavior_dt = datetime.fromtimestamp(ts).strftime('%Y-%m-%d %H:%M:%S')

                cursor.execute(insert_sql, (
                    event_id,
                    int(row['user_id']),
                    int(row['item_id']),
                    int(row['category_id']),
                    row['behavior_type'],
                    ts,
                    behavior_dt,
                    float(row['unit_price']),
                    int(row['qty'])
                ))
                success_count += 1
            except Exception as e:
                error_count += 1
                if error_count <= 3:
                    print(f"    [Warning] 第{idx}行写入失败: {e}")

        conn.commit()
        cursor.close()
        conn.close()

        print(f"\n  ✅ 数据入库完成！")
        print(f"  成功写入: {success_count} 条")
        print(f"  写入失败: {error_count} 条")

    except pymysql.Error as e:
        print(f"\n  ❌ 数据库操作失败: {e}")
        print(f"  提示: 请确保MySQL服务已启动，并检查连接配置。")


def show_summary(df):
    """输出预处理结果总结"""
    print("\n" + "=" * 60)
    print("           数据预处理结果总结")
    print("=" * 60)

    print(f"\n  最终数据行数: {len(df)}")
    print(f"  最终数据列数: {len(df.columns)}")
    print(f"\n  列名一览: {df.columns.tolist()}")

    print(f"\n  各行为类型分布:")
    behavior_dist = df['behavior_type'].value_counts()
    for btype, count in behavior_dist.items():
        pct = count / len(df) * 100
        print(f"    {btype:6s}: {count:6d} 条 ({pct:.1f}%)")

    print(f"\n  用户数量: {df['user_id'].nunique()}")
    print(f"  商品数量: {df['item_id'].nunique()}")
    print(f"  类目数量: {df['category_id'].nunique()}")

    print("\n" + "=" * 60)
    print("           预处理流程全部完成！")
    print("=" * 60)


# ==========================================
# 主流程
# ==========================================
if __name__ == '__main__':
    # 1. 加载数据
    df = load_data(CSV_FILE)

    # 2. 数据预处理
    # 5.2.1 重复值处理
    df = handle_duplicates(df)

    # 5.2.2 缺失值处理
    df = handle_missing_values(df)

    # 5.2.3 数据类型转换
    df = convert_data_types(df)

    # 5.2.4 数据归一化
    df = normalize_data(df)

    # 3. 输出总结
    show_summary(df)

    # 4. 存入数据库
    save_to_database(df)
