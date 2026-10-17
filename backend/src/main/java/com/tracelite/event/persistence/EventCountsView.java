package com.tracelite.event.persistence;

public interface EventCountsView {

    long getTotalEvents();

    long getEventsSinceLastDay();

    long getEventsSinceLastWeek();
}
