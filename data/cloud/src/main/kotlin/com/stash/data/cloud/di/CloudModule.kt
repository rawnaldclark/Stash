package com.stash.data.cloud.di

import com.stash.core.data.db.dao.CloudDao
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.CloudConfig
import com.stash.data.cloud.auth.CloudTokenManager
import com.stash.data.cloud.auth.CloudTokenStore
import com.stash.data.cloud.auth.EncryptedCloudTokenStore
import com.stash.data.cloud.auth.ProviderAuth
import com.stash.data.cloud.net.CloudHttp
import com.stash.data.cloud.net.RangeFetcher
import com.stash.data.cloud.net.RequestGate
import com.stash.data.cloud.playback.CloudLinkResolver
import com.stash.data.cloud.provider.CloudStorageProvider
import com.stash.data.cloud.provider.dropbox.DropboxAuth
import com.stash.data.cloud.provider.dropbox.DropboxProvider
import com.stash.data.cloud.provider.gdrive.GoogleAuthorizer
import com.stash.data.cloud.provider.gdrive.GoogleDriveAuth
import com.stash.data.cloud.provider.gdrive.GoogleDrivePicks
import com.stash.data.cloud.provider.gdrive.GoogleDriveProvider
import com.stash.data.cloud.provider.gdrive.GooglePickStore
import com.stash.data.cloud.provider.gdrive.PlayServicesGoogleAuthorizer
import com.stash.data.cloud.provider.gdrive.PrefsGooglePickStore
import com.stash.data.cloud.provider.onedrive.OneDriveAuth
import com.stash.data.cloud.provider.onedrive.OneDriveProvider
import com.stash.data.cloud.scan.CloudArtStore
import com.stash.data.cloud.scan.CloudScanScheduler
import com.stash.data.cloud.scan.CloudScanner
import com.stash.data.cloud.upload.AndroidLocalAudioFiles
import com.stash.data.cloud.upload.CloudUploadSettings
import com.stash.data.cloud.upload.DriveFolders
import com.stash.data.cloud.upload.DriveUploadApi
import com.stash.data.cloud.upload.DriveUploader
import com.stash.data.cloud.upload.LocalAudioFiles
import com.stash.data.cloud.upload.PrefsCloudUploadSettings
import com.stash.data.cloud.upload.ResumableUpload
import com.stash.data.cloud.upload.UploadNaming
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Wires the cloud providers. [CloudConfig] comes from the app module (BuildConfig keys).
 *
 * Two HTTP clients: API calls use the shared client (its debug logging redacts the
 * Authorization header); reads of file bytes use a copy with every interceptor removed, so
 * pre-authenticated links (which are credentials) and Google Drive's bearer header never reach a
 * log.
 */
@Module
@InstallIn(SingletonComponent::class)
object CloudModule {

    @Provides
    @Singleton
    @Named(CONTENT_CLIENT)
    fun provideContentClient(shared: OkHttpClient): OkHttpClient = shared.newBuilder()
        .apply { interceptors().clear(); networkInterceptors().clear() }
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRangeFetcher(@Named(CONTENT_CLIENT) client: OkHttpClient): RangeFetcher = RangeFetcher(client)

    @Provides
    @Singleton
    fun provideDropboxAuth(config: CloudConfig, client: OkHttpClient): DropboxAuth = DropboxAuth(config.dropboxAppKey, client)

    @Provides
    @Singleton
    fun provideOneDriveAuth(config: CloudConfig, client: OkHttpClient): OneDriveAuth =
        OneDriveAuth(config.oneDriveClientId, config.applicationId, client)

    @Provides
    @Singleton
    fun provideGoogleDriveAuth(authorizer: GoogleAuthorizer, client: OkHttpClient): GoogleDriveAuth =
        GoogleDriveAuth(authorizer, client)

    /** Only providers this build has credentials for: an unconfigured one can't even start a sign-in. */
    @Provides
    @Singleton
    fun provideAuths(
        config: CloudConfig,
        dropbox: DropboxAuth,
        oneDrive: OneDriveAuth,
        googleDrive: GoogleDriveAuth,
    ): Map<CloudProvider, ProviderAuth> =
        buildMap {
            if (config.isEnabled(CloudProvider.DROPBOX)) put(CloudProvider.DROPBOX, dropbox)
            if (config.isEnabled(CloudProvider.ONEDRIVE)) put(CloudProvider.ONEDRIVE, oneDrive)
            if (config.isEnabled(CloudProvider.GOOGLE_DRIVE)) put(CloudProvider.GOOGLE_DRIVE, googleDrive)
        }

    @Provides
    @Singleton
    fun provideProviders(
        config: CloudConfig,
        client: OkHttpClient,
        dropboxAuth: DropboxAuth,
        oneDriveAuth: OneDriveAuth,
        googleDriveAuth: GoogleDriveAuth,
        tokens: CloudTokenManager,
        dao: CloudDao,
        picks: GooglePickStore,
        @Named(DRIVE_GATE) driveGate: RequestGate,
    ): Map<CloudProvider, CloudStorageProvider> = mapOf(
        // One gate per provider: each has its own rate limits.
        CloudProvider.DROPBOX to DropboxProvider(config.dropboxAppKey, dropboxAuth, CloudHttp(client, RequestGate()), tokens),
        CloudProvider.ONEDRIVE to OneDriveProvider(config.oneDriveClientId, oneDriveAuth, CloudHttp(client, RequestGate()), tokens),
        CloudProvider.GOOGLE_DRIVE to GoogleDriveProvider(
            config.isEnabled(CloudProvider.GOOGLE_DRIVE), googleDriveAuth, CloudHttp(client, driveGate), tokens, dao, picks,
        ),
    )

    /** Google Drive's one request gate: the scan, playback links and uploads share its limits. */
    @Provides
    @Singleton
    @Named(DRIVE_GATE)
    fun provideDriveGate(): RequestGate = RequestGate()

    /**
     * Uploads talk to Drive through the content client (no logging interceptors): a resumable
     * session address is a credential and comes back in a header.
     */
    @Provides
    @Singleton
    fun provideDriveUploadApi(
        @Named(CONTENT_CLIENT) client: OkHttpClient,
        @Named(DRIVE_GATE) gate: RequestGate,
        tokens: CloudTokenManager,
    ): DriveUploadApi = DriveUploadApi(CloudHttp(client, gate), tokens)

    @Provides
    @Singleton
    fun provideDriveUploader(
        dao: CloudDao,
        api: DriveUploadApi,
        settings: CloudUploadSettings,
        @Named(CONTENT_CLIENT) client: OkHttpClient,
        @Named(DRIVE_GATE) gate: RequestGate,
        files: LocalAudioFiles,
        naming: UploadNaming,
    ): DriveUploader = DriveUploader(
        dao = dao,
        api = api,
        folders = DriveFolders(api, settings),
        session = ResumableUpload(client.newBuilder().writeTimeout(60, TimeUnit.SECONDS).build(), gate, files),
        files = files,
        naming = naming,
    )

    @Provides
    @Singleton
    fun provideGoogleDrivePicks(
        dao: CloudDao,
        tokens: CloudTokenManager,
        auth: GoogleDriveAuth,
        store: GooglePickStore,
        scheduler: CloudScanScheduler,
    ): GoogleDrivePicks = GoogleDrivePicks(dao, tokens, auth, store, scanNow = { folderId ->
        scheduler.scanNow(folderId)
        scheduler.ensurePeriodic()
    })

    @Provides
    @Singleton
    fun provideLinkResolver(providers: Map<CloudProvider, @JvmSuppressWildcards CloudStorageProvider>): CloudLinkResolver =
        CloudLinkResolver(providers)

    @Provides
    @Singleton
    fun provideScanner(
        dao: CloudDao,
        providers: Map<CloudProvider, @JvmSuppressWildcards CloudStorageProvider>,
        links: CloudLinkResolver,
        ranges: RangeFetcher,
        art: CloudArtStore,
    ): CloudScanner = CloudScanner(
        dao, providers, links, { link, offset, length -> ranges.fetch(link.url, offset, length, link.headers) }, art::save,
        // Songs Stash saved to Drive that are already downloads here are linked, not added twice.
        localSongs = { com.stash.data.cloud.upload.UploadKeys.downloadsByKey(dao) },
    )

    const val CONTENT_CLIENT = "cloud_content"
    const val DRIVE_GATE = "gdrive_gate"
}

@Module
@InstallIn(SingletonComponent::class)
interface CloudBindings {
    @Binds
    fun tokenStore(impl: EncryptedCloudTokenStore): CloudTokenStore

    @Binds
    fun googleAuthorizer(impl: PlayServicesGoogleAuthorizer): GoogleAuthorizer

    @Binds
    fun googlePickStore(impl: PrefsGooglePickStore): GooglePickStore

    @Binds
    fun uploadSettings(impl: PrefsCloudUploadSettings): CloudUploadSettings

    @Binds
    fun localAudioFiles(impl: AndroidLocalAudioFiles): LocalAudioFiles

    @Binds
    fun uploadRunner(impl: com.stash.data.cloud.upload.CloudUploadScheduler): com.stash.data.cloud.upload.CloudUploadRunner
}
