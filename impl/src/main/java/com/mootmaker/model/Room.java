package com.mootmaker.model;

import module java.base;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public record Room(String id, String name, int capacity, RoomColor color) {

  /**
   * Convenience for the many call sites that don't care about colour - a brand new room, and every
   * existing test fixture written before this field existed. {@code color} defaults to {@code
   * null}: no explicit colour chosen, same as every room behaved before this field existed.
   */
  public Room(final String id, final String name, final int capacity) {
    this(id, name, capacity, null);
  }

  public Map<String, AttributeValue> toItem() {
    final Map<String, AttributeValue> item = new HashMap<>();
    item.put("id", AttributeValue.builder().s(id).build());
    item.put("name", AttributeValue.builder().s(name).build());
    item.put("capacity", AttributeValue.builder().n(String.valueOf(capacity)).build());
    if (color != null) {
      item.put("color", AttributeValue.builder().s(color.name()).build());
    }
    return item;
  }

  public Map<String, Object> toResponseMap() {
    final Map<String, Object> map = new HashMap<>();
    map.put("id", id);
    map.put("name", name);
    map.put("capacity", capacity);
    map.put("color", color == null ? null : color.name());
    return map;
  }

  public static Room fromItem(final Map<String, AttributeValue> item) {
    final AttributeValue color = item.get("color");
    return new Room(
        item.get("id").s(),
        item.get("name").s(),
        Integer.parseInt(item.get("capacity").n()),
        color == null ? null : RoomColor.valueOf(color.s()));
  }
}
