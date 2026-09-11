package com.nextaicommerce.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class TablePagingTest {
    @Test void acceptsOnlyStandardPageSizes() {
        for (int value : new int[]{25, 50, 100}) assertThat(TablePaging.size(value)).isEqualTo(value);
        for (int value : new int[]{-1, 0, 1, 26, 200, Integer.MAX_VALUE})
            assertThat(TablePaging.size(value)).isEqualTo(25);
    }
}
