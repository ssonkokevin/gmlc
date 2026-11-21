package com.hamiltel.gmlc.cell;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With an area from the network the plan lookup is the canonical identity, so
 * a cell code reused in another LAC or TAC is not mistaken for this one.
 */
class CellPlanSelectorTest {

    @Test
    void theAreaIsPartOfTheLookupAndTheIdentityIsCanonical() {
        AtomicReference<String> looked = new AtomicReference<>();
        CellRepository repository = new CellRepository(null) {
            @Override
            public Optional<CellRow> find(long tenantId, CellIdentity.Rat rat, String identity) {
                looked.set(rat + "/" + identity);
                return Optional.empty();
            }
        };

        Optional<CellRepository.CellRow> row = new CellPlanSelector(repository)
                .selectInArea(1, "GSM", "001", "01", "04660", "0001234");

        assertThat(row).isEmpty();
        assertThat(looked.get()).isEqualTo("GSM/001-01-4660-1234");
    }
}
