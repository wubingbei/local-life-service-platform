package com.llsp.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：验证 tb_voucher_order 的 (user_id, voucher_id) 唯一索引生效
 * 依赖真实 MySQL，且需先执行 ALTER TABLE 添加 uk_user_voucher 索引
 */
@SpringBootTest
class VoucherOrderUniqueIndexIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void duplicateUserVoucher_shouldBeRejectedByUniqueIndex() {
        // 1. 确认唯一索引已存在
        Integer idxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                        "WHERE table_schema = DATABASE() AND table_name = 'tb_voucher_order' " +
                        "AND index_name = 'uk_user_voucher'",
                Integer.class);
        assertTrue(idxCount != null && idxCount > 0,
                "唯一索引 uk_user_voucher 不存在，请先执行: " +
                        "ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id)");

        long userId = 9_000_000_000L;
        long voucherId = 8_000_000_000L;
        long id1 = System.currentTimeMillis();
        long id2 = id1 + 1;

        // 2. 清理历史残留测试数据
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE user_id = ? AND voucher_id = ?", userId, voucherId);

        try {
            // 3. 第一条插入成功
            jdbcTemplate.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id) VALUES (?, ?, ?)",
                    id1, userId, voucherId);

            // 4. 第二条重复 (user_id, voucher_id) 应被唯一索引拦截
            assertThrows(DuplicateKeyException.class, () ->
                    jdbcTemplate.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id) VALUES (?, ?, ?)",
                            id2, userId, voucherId)
            );
        } finally {
            // 5. 清理测试数据
            jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE user_id = ? AND voucher_id = ?", userId, voucherId);
        }
    }
}
