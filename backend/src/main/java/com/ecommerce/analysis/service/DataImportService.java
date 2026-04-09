package com.ecommerce.analysis.service;

import com.ecommerce.analysis.vo.ImportStatusVO;

/**
 * 数据导入服务接口
 */
public interface DataImportService {

    /**
     * 导入CSV数据文件
     * 
     * @param filePath  CSV文件路径
     * @param batchSize 批量插入大小
     * @param maxRows   最大导入行数(0表示不限制)
     * @return 导入的记录数
     */
    void importCsvData(String filePath, int batchSize, long maxRows);

    /**
     * 获取导入状态
     */
    ImportStatusVO getImportStatus();

    /**
     * 是否正在导入
     */
    boolean isImporting();

    /**
     * 停止导入
     */
    void stopImport();
}
