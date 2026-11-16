package com.tracedesk.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "departments")
public class Department {

    @Id
    private Short id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    public Department() {
    }

    public Department(Short id, String name) {
        this.id = id;
        this.name = name;
    }

    public Short getId() {
        return id;
    }

    public void setId(Short id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
