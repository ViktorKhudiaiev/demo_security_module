package com.demo.integrity.dto;

import java.util.List;

/** Paginated receipt inventory shared by the key service and processor client. */
public record IssuanceInventory(List<IssuanceInventoryItem> items, long nextSequence) {}
