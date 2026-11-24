package com.scientificlens.harness.core;

import com.scientificlens.harness.domain.StoredAnalysis;
import java.util.List;

public record LibraryListing(List<StoredAnalysis> entries, List<String> unreadableFiles) {
    public LibraryListing {
        entries = List.copyOf(entries);
        unreadableFiles = List.copyOf(unreadableFiles);
    }
}
