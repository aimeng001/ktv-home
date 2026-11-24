package com.homektv.web;

import com.homektv.ai.AiLibraryService;
import com.homektv.common.BackgroundJobStatusProvider;
import com.homektv.library.ArtistAvatarJobService;
import com.homektv.library.IktvSongMatchBatchService;
import com.homektv.library.LibraryScanService;
import com.homektv.musicsource.MusicMetadataScrapeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers adapters from the five background job coordinators without adding interfaces
 * directly onto AOP-proxied service classes (which would force JDK proxies instead of class proxies).
 */
@Configuration(proxyBeanMethods = false)
class BackgroundJobOverviewConfiguration {

    @Bean
    BackgroundJobStatusProvider libraryScanJobStatusProvider(ObjectProvider<LibraryScanService> service) {
        return () -> service.getIfAvailable() == null ? null : service.getObject().jobStatus();
    }

    @Bean
    BackgroundJobStatusProvider metadataScrapeJobStatusProvider(ObjectProvider<MusicMetadataScrapeService> service) {
        return () -> service.getIfAvailable() == null ? null : service.getObject().jobStatus();
    }

    @Bean
    BackgroundJobStatusProvider artistAvatarJobStatusProvider(ObjectProvider<ArtistAvatarJobService> service) {
        return () -> service.getIfAvailable() == null ? null : service.getObject().jobStatus();
    }

    @Bean
    BackgroundJobStatusProvider iktvFusionJobStatusProvider(ObjectProvider<IktvSongMatchBatchService> service) {
        return () -> service.getIfAvailable() == null ? null : service.getObject().jobStatus();
    }

    @Bean
    BackgroundJobStatusProvider aiAnalysisJobStatusProvider(ObjectProvider<AiLibraryService> service) {
        return () -> service.getIfAvailable() == null ? null : service.getObject().jobStatus();
    }
}
