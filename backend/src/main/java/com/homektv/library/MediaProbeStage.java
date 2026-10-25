package com.homektv.library;

import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.resource.ResourceBudget;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;

/** Runs read-only FFprobe work under the shared admission budget and returns an outcome. */
@Component
public final class MediaProbeStage {
    private final FFprobeService ffprobe;
    private final ResourceBudget resourceBudget;

    public MediaProbeStage(FFprobeService ffprobe, ResourceBudget resourceBudget) {
        this.ffprobe = Objects.requireNonNull(ffprobe, "ffprobe");
        this.resourceBudget = Objects.requireNonNull(resourceBudget, "resourceBudget");
    }

    public Result probe(Path file) {
        try {
            return new Result(probeRequired(file), null, true);
        } catch (ProbeAdmissionInterruptedException interrupted) {
            return new Result(null, interrupted.getCause(), false);
        } catch (RuntimeException failure) {
            return new Result(null, failure, true);
        }
    }

    /** Runs a required probe and preserves media failures for worker callers. */
    public MediaProbe probeRequired(Path file) {
        try (ResourceBudget.Permit ignored = resourceBudget.acquireProbe()) {
            return ffprobe.probe(file);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProbeAdmissionInterruptedException(interrupted);
        }
    }

    public static final class ProbeAdmissionInterruptedException extends IllegalStateException {
        public ProbeAdmissionInterruptedException(InterruptedException cause) {
            super("Interrupted while waiting for media probe capacity", cause);
        }
    }

    public record Result(MediaProbe probe, Throwable failure, boolean attempted) {}
}
