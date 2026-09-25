package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code RoomColor} enum. Constant names must match the schema's enum value
 * names exactly, since AppSync serializes/validates enum values as these literal strings.
 *
 * <p>One of a fixed 8-hue palette (see {@code roomPaletteLight}/{@code roomPaletteDark} in the
 * webapp) validated for accessibility in both light and dark mode - never an arbitrary colour, so
 * no server-side contrast validation is needed. Order matches that palette's own array order,
 * though nothing here depends on it; the webapp maps each name to its own index independently.
 */
public enum RoomColor {
  Blue,
  Orange,
  Aqua,
  Yellow,
  Magenta,
  Green,
  Violet,
  Red
}
