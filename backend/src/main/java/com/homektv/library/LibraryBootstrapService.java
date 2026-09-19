package com.homektv.library;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Submits the first external-library scan after Flyway and application startup. */
@Component
public class LibraryBootstrapService {
    private final LibraryScanCoordinator coordinator;

    public LibraryBootstrapService(LibraryScanCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        coordinator.requestBootstrap();
    }
}
