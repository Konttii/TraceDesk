package com.tracedesk.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "regions")
public class Region {

    @Id
    @Column(length = 2)
    private String id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    public Region() {
    }

    public Region(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
