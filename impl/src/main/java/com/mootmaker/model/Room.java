package com.mootmaker.model;

import module java.base;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public record Room(String id, String name, int capacity) {

  public Map<String, AttributeValue> toItem() {
    final Map<String, AttributeValue> item = new HashMap<>();
    item.put("id", AttributeValue.builder().s(id).build());
    item.put("name", AttributeValue.builder().s(name).build());
    item.put("capacity", AttributeValue.builder().n(String.valueOf(capacity)).build());
    return item;
  }

  public Map<String, Object> toResponseMap() {
    final Map<String, Object> map = new HashMap<>();
    map.put("id", id);
    map.put("name", name);
    map.put("capacity", capacity);
    return map;
  }

  public static Room fromItem(final Map<String, AttributeValue> item) {
    return new Room(
        item.get("id").s(), item.get("name").s(), Integer.parseInt(item.get("capacity").n()));
  }
}
