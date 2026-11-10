package com.tracedesk.domain.entity;

import com.tracedesk.domain.enums.TicketStatus;
import jakarta.persistence.*;
import java.time.OffsetDateTime;


@Entity
@Table(name = "ticket_status_history")
public class TicketStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY,optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", length = 20)
    private TicketStatus olsStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status",nullable = false,length = 20)
    private TicketStatus newStatus;

    @ManyToOne(fetch = FetchType.LAZY,optional = false)
    @JoinColumn(name = "changed_by_id")
    private User changeBy;

    @Column(name = "changed_at",nullable = false,updatable = false)
    private OffsetDateTime changedAt;

    public TicketStatusHistory(){
    }

    public TicketStatusHistory(Ticket ticket, TicketStatus oldStatus, TicketStatus newStatus, User changeBy){
        this.ticket = ticket;
        this.olsStatus = oldStatus;
        this.newStatus = newStatus;
        this.changeBy = changeBy;
    }

    @PrePersist
    public void prePersist(){
        if (this.changedAt == null){
            changedAt = OffsetDateTime.now();
        }
    }

    public Long getId(){
        return id;
    }

    public Ticket getTicket(){
        return ticket;
    }

    public TicketStatus getOldStatus(){
        return olsStatus;
    }

    public TicketStatus getNewStatus(){
        return newStatus;
    }

    public User getChangeBy(){
        return changeBy;
    }

    public OffsetDateTime getChangedAt(){
        return changedAt;
    }
}
