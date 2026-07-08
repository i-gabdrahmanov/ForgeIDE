package dev.forgeide.core.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TileValidityTest {

    @Test
    void unknownCarriesNoDetail() {
        TileValidity validity = TileValidity.unknown();

        assertThat(validity.status()).isEqualTo(TileValidityStatus.UNKNOWN);
        assertThat(validity.detail()).isEmpty();
    }

    @Test
    void freshAndStaleCarryTheGivenDetail() {
        assertThat(TileValidity.fresh("owner: forge-team")).isEqualTo(
                new TileValidity(TileValidityStatus.FRESH, "owner: forge-team"));
        assertThat(TileValidity.stale("expired 2026-01")).isEqualTo(
                new TileValidity(TileValidityStatus.STALE, "expired 2026-01"));
    }
}
