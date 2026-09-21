package io.arcnode.mockderms.dispatch.dto;

/**
 * Canonical arcnode float measurement payload (system_adr §12/§13). Serialized as {@code {"ts":
 * "...", "value": <number>}}.
 *
 * @param ts RFC3339 / ISO-8601 UTC timestamp with trailing {@code Z}
 * @param value engineering value in the topic's unit slot
 */
public record FloatSample(String ts, double value) {}
