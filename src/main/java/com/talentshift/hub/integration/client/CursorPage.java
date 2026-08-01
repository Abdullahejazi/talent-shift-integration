package com.talentshift.hub.integration.client;

import java.util.List;

public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {}
