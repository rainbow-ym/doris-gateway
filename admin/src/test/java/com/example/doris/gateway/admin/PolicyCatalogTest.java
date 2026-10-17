package com.example.doris.gateway.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyCatalogTest {

    private final PolicyCatalog catalog = new PolicyCatalog();

    @Test
    void recommendsPoliciesFromChineseColumnNames() {
        assertThat(catalog.recommend("证件号码", "")).isEqualTo("ID_CARD");
        assertThat(catalog.recommend("姓名", "")).isEqualTo("PERSON_NAME");
        assertThat(catalog.recommend("手机号", "")).isEqualTo("MOBILE_PHONE");
        assertThat(catalog.recommend("地址", "")).isEqualTo("ADDRESS");
    }

    @Test
    void recommendsPolicyFromSecurityTagInComment() {
        assertThat(catalog.recommend("unrecognized_column", "业务字段 SECURITY:ID_CARD"))
                .isEqualTo("ID_CARD");
    }

    @Test
    void leavesUnknownColumnsUnassigned() {
        assertThat(catalog.recommend("created_at", "创建时间")).isEqualTo(PolicyCatalog.NONE);
    }
}
