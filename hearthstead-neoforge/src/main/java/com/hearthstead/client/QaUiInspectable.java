package com.hearthstead.client;

/**
 * Optional, allocation-free-until-queried UI evidence for the physical-input
 * release harness. Implementations return a compact state token only when the
 * dormant QA observer acknowledges an explicit frame barrier.
 */
public interface QaUiInspectable {
    String qaUiState();
}
