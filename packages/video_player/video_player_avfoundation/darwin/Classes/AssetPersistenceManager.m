#import "AssetPersistenceManager.h"

@implementation AssetPersistenceManager

/// The AVAssetDownloadURLSession to use for managing AVAssetDownloadTasks.
AVAssetDownloadURLSession* assetDownloadURLSession;

/// Internal map of AVAggregateAssetDownloadTask to its corresponding Asset.
NSMutableDictionary *activeDownloadsMap;

/// Internal map of AVAggregateAssetDownloadTask to download URL.
NSMutableDictionary *willDownloadToUrlMap;

/// Singleton for AssetPersistenceManager.
+ (AssetPersistenceManager *)sharedManager {
    static AssetPersistenceManager *sharedManager = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        sharedManager = [[self alloc] init];
        sharedManager.didRestorePersistenceManager = false;
    });
    return sharedManager;
}

- (id)init {
    if (self = [super init]) {
        activeDownloadsMap = [NSMutableDictionary dictionary];
        willDownloadToUrlMap = [NSMutableDictionary dictionary];
        
        NSURLSessionConfiguration* backgroundConfiguration = [NSURLSessionConfiguration backgroundSessionConfigurationWithIdentifier:@"Gismart-Identifier"];
        // Default behavior is to retry a failed task constantly for 7 days,
        // which halts all other background tasks in case of repeated failures
        NSTimeInterval timeoutInSeconds = 30.0;
        backgroundConfiguration.timeoutIntervalForRequest = timeoutInSeconds;
        backgroundConfiguration.timeoutIntervalForResource = timeoutInSeconds;
        
        assetDownloadURLSession = [AVAssetDownloadURLSession sessionWithConfiguration:backgroundConfiguration
                                                                assetDownloadDelegate:self
                                                                        delegateQueue:NSOperationQueue.mainQueue];
    }
    return self;
}

/// Restores the Application state by getting all the AVAssetDownloadTasks and restoring their Asset structs.
- (void)restorePersistenceManager {
    if(_didRestorePersistenceManager) { return; }
    
    _didRestorePersistenceManager = true;
    
    // Grab all the tasks associated with the assetDownloadURLSession
    [assetDownloadURLSession getAllTasksWithCompletionHandler:^(NSArray* tasksArray) {
        for (NSURLSessionTask *task in tasksArray) {
            if ([task isKindOfClass:[AVAggregateAssetDownloadTask class]]) {
                AVAggregateAssetDownloadTask* assetDownloadTask = (AVAggregateAssetDownloadTask *)task;
                NSString* assetName = task.taskDescription;
                if(assetDownloadTask != nil && assetName != nil) {
                    AVURLAsset* urlAsset = assetDownloadTask.URLAsset;
                    
                    Asset* asset = [[Asset alloc] initWithURLAsset:urlAsset];
                    
                    activeDownloadsMap[assetDownloadTask] = asset;
                }
            }
        }
    }];
}

/// Triggers the initial AVAssetDownloadTask for a given Asset.
- (void)downloadStream:(Asset *)asset streamName:(NSString *)streamName audioTrackName:(NSString *)audioTrackName {
    // Get the default media selections for the asset's media selection groups.
    AVMediaSelection* preferredMediaSelection = asset.urlAsset.preferredMediaSelection;
    NSMutableArray *finalMediaSelections = [[NSMutableArray alloc] init];

    if(audioTrackName != nil) {
        AVMediaSelectionGroup *audioSelectionGroup = [asset.urlAsset mediaSelectionGroupForMediaCharacteristic: AVMediaCharacteristicAudible];
        if(audioSelectionGroup != nil) {
            NSArray* audioSelectionGroupOptions = audioSelectionGroup.options;
            NSMutableArray* audioTrackNames = [NSMutableArray array];
            for(AVMediaSelectionOption* audioTrack in audioSelectionGroupOptions) {
                NSString* localAudioTrackName = audioTrack.locale.languageCode;
                if(audioTrack.locale.languageCode == nil) {
                    localAudioTrackName = @"und"; // as defined in ISO 639-2
                }
         
                if([audioTrackName isEqualToString:localAudioTrackName]) {
                    AVMutableMediaSelection* mutableMediaSelection = [preferredMediaSelection mutableCopy];
                    [mutableMediaSelection selectMediaOption:audioTrack inMediaSelectionGroup:audioSelectionGroup];
                    [finalMediaSelections addObject:mutableMediaSelection];
                    break;
                }
            }
        }
    }

    if([finalMediaSelections count] == 0) {
        [finalMediaSelections addObject:preferredMediaSelection];
    }

    /*
     Creates and initializes an AVAggregateAssetDownloadTask to download multiple AVMediaSelections
     on an AVURLAsset.
     */
    @try {
        AVAggregateAssetDownloadTask* task = [assetDownloadURLSession aggregateAssetDownloadTaskWithURLAsset: asset.urlAsset
                                                                                             mediaSelections: finalMediaSelections
                                                                                                  assetTitle: streamName
                                                                                            assetArtworkData: nil
                                                                                                     options: nil];
        
        task.taskDescription = asset.uniqueId;

        activeDownloadsMap[task] = asset;

        [task resume];
    } @catch (NSException *exception) {
        NSLog(@"An error occured while trying to download stream:%@", exception);
    }
}

/// Returns an Asset given a specific name if that Asset is associated with an active download.
- (Asset *)assetForStream:(NSString *)uniqueId {
    __block Asset* asset;

    [activeDownloadsMap enumerateKeysAndObjectsUsingBlock: ^(id _, id assetValue, BOOL *stop) {
        if ([assetValue isKindOfClass:[Asset class]]) {
            Asset* localAsset = (Asset *)assetValue;
            if(localAsset != nil) {
                if([uniqueId isEqualToString:localAsset.uniqueId]) {
                    asset = assetValue;
                    *stop = YES;
                }
            }
        }
    }];
    
    return asset;
}

/// Returns an Asset pointing to a file on disk if it exists.
- (Asset *)localAssetForStream:(NSString *)uniqueId {
    NSUserDefaults* userDefaults = [NSUserDefaults standardUserDefaults];
    NSData* localFileLocation = [userDefaults dataForKey:uniqueId];
    if(localFileLocation != nil) {
        Asset* asset;
        bool bookmarkDataIsStale = false;
        @try {
            NSURL* url = [NSURL URLByResolvingBookmarkData:localFileLocation
                                                   options:NSURLBookmarkResolutionWithoutUI
                                             relativeToURL:nil
                                       bookmarkDataIsStale:&bookmarkDataIsStale
                                                     error:nil];

            if(bookmarkDataIsStale) {
                NSLog(@"FATAL ERROR: Bookmark data is stale!");
                return nil;
            }
            
            AVURLAsset *urlAsset = [AVURLAsset assetWithURL:url];
            
            asset = [[Asset alloc] initWithURLAsset:urlAsset];
            
            return asset;
        } @catch (NSException *exception) {
            NSLog(@"Failed to create URL from bookmark with error:%@", exception);
            return nil;
        }
    } else {
        return nil;
    }
}

/// Returns the current download state for a given Asset.
- (AssetDownloadState)downloadState:(Asset *)asset audioTrackName:(NSString *)audioTrackName{
    // Check if there is a file URL stored for this asset.
    Asset* localAsset = [self localAssetForStream:asset.uniqueId];
    if (localAsset != nil) {
        NSURL* localFileLocation = localAsset.urlAsset.URL;
        
        // Check if the file exists on disk
        NSFileManager* defaultFileManager = [NSFileManager defaultManager];
        if ([defaultFileManager fileExistsAtPath:localFileLocation.path]) {
            AVURLAsset *urlAsset = [AVURLAsset assetWithURL:localFileLocation];
            AVAssetCache *assetCache = urlAsset.assetCache;
            if(!assetCache.isPlayableOffline) {
                return AssetDownloading;
            }
            
            if(audioTrackName != nil) {
                // Check if requested audio track is cached
                AVMediaSelectionGroup *audioSelectionGroup = [urlAsset mediaSelectionGroupForMediaCharacteristic: AVMediaCharacteristicAudible];
                if(audioSelectionGroup != nil) {
                    NSArray *audioSelectionGroupOptions = [assetCache mediaSelectionOptionsInMediaSelectionGroup:audioSelectionGroup];
                    for(AVMediaSelectionOption* audioTrack in audioSelectionGroupOptions) {
                        NSString* localAudioTrackName = audioTrack.locale.languageCode;
                        if(audioTrack.locale.languageCode == nil) {
                            localAudioTrackName = @"und"; // as defined in ISO 639-2
                        }
                        
                        if([audioTrackName isEqualToString:localAudioTrackName]) {
                            return AssetDownloaded;
                        }
                    }
                }
                [self deleteAsset:asset];
                return AssetNotDownloaded;
            }
            return AssetDownloaded;
        }
    }

    // Check if there are any active downloads in queue.
    __block bool isAssetDownloading = false;
    [activeDownloadsMap enumerateKeysAndObjectsUsingBlock: ^(id _, id assetValue, BOOL *stop) {
        if ([assetValue isKindOfClass:[Asset class]]) {
            Asset* localAsset = (Asset *)assetValue;
            if(localAsset != nil && [asset.uniqueId isEqualToString:localAsset.uniqueId]) {
                isAssetDownloading = true;
                *stop = YES;
            }
        }
    }];
    if(isAssetDownloading) {
        return AssetDownloading;
    } else {
        return AssetNotDownloaded;
    }
}

/// Deletes an Asset on disk if possible.
- (void)deleteAsset:(Asset *)asset {
    NSUserDefaults* userDefaults = [NSUserDefaults standardUserDefaults];

    @try {
        Asset* localAsset = [self localAssetForStream:asset.uniqueId];
        if(localAsset != nil) {
            NSURL* localFileLocation = localAsset.urlAsset.URL;

            NSFileManager* defaultFileManager = [NSFileManager defaultManager];
            [defaultFileManager removeItemAtURL:localFileLocation error:nil];

            [userDefaults removeObjectForKey:asset.uniqueId];
        }
    } @catch (NSException *exception) {
        NSLog(@"An error occured deleting offline HLS asset:%@", exception);
    }
}

/// Cancels an AVAssetDownloadTask given an Asset.
- (void)cancelDownload:(Asset *)asset {
    __block AVAggregateAssetDownloadTask* task;

    [activeDownloadsMap enumerateKeysAndObjectsUsingBlock: ^(id taskKey, id assetValue, BOOL *stop) {
        if ([assetValue isKindOfClass:[Asset class]]) {
            Asset* localAsset = (Asset *)assetValue;
            if(localAsset != nil && [asset.uniqueId isEqualToString:localAsset.uniqueId]) {
                task = taskKey;
                *stop = YES;
            }
        }
    }];

    if(task != nil) {
        [task cancel];
    }
}

// Implementation of `AVAssetDownloadDelegate` protocol.

/// Tells the delegate that the task finished transferring data.
- (void)URLSession:(NSURLSession *)session
              task:(NSURLSessionTask *)task
didCompleteWithError:(NSError *)error {
    NSLog(@"HLS Asset: Completed download");
    
    NSUserDefaults* userDefaults = [NSUserDefaults standardUserDefaults];
    
    /*
     This is the place to begin downloading additional media selections
     once the asset itself has finished downloading.
     */
    AVAggregateAssetDownloadTask* aggregateAssetDownloadTask = (AVAggregateAssetDownloadTask *)task;
    if(aggregateAssetDownloadTask != nil) {
        Asset* asset = activeDownloadsMap[task];
        if(asset != nil) {
            [activeDownloadsMap removeObjectForKey:task];

            NSURL* downloadURL = willDownloadToUrlMap[task];
            if(downloadURL != nil) {
                [willDownloadToUrlMap removeObjectForKey:task];

                if(error != nil) {
                    switch(error.code) {
                        case NSURLErrorUnknown: {
                            NSLog(@"Downloading HLS streams is not supported in the simulator.");
                            return;
                        }
                        default: {
                            /*
                             This task was cancelled or failed, should perform cleanup manually.
                             */
                            NSLog(@"Failed to finish downloading an HLS stream, performing clean-up.");
                            
                            NSFileManager* defaultFileManager = [NSFileManager defaultManager];
                            @try {
                                [defaultFileManager removeItemAtURL:downloadURL error:nil];
                                
                                [userDefaults removeObjectForKey:asset.uniqueId];
                            } @catch (NSException *exception) {
                                NSLog(@"An error occured trying to delete the contents on disk for:%@: %@", asset.uniqueId, error);
                            }
                            break;
                        }
                    }
                } else {
                    @try {
                        NSData* bookmark = [downloadURL bookmarkDataWithOptions:NSURLBookmarkCreationMinimalBookmark includingResourceValuesForKeys:nil relativeToURL:nil error:nil];
                        
                        [userDefaults setObject:bookmark forKey:asset.uniqueId];
                    } @catch (NSException *exception) {
                        NSLog(@"Failed to create bookmarkData for download URL.");
                    }
                }
            }
        }
    }
}

/// Method called when the an aggregate download task determines the location this asset will be downloaded to.
- (void)URLSession:(NSURLSession *)session
aggregateAssetDownloadTask:(AVAggregateAssetDownloadTask *)aggregateAssetDownloadTask
 willDownloadToURL:(NSURL *)location {
    NSLog(@"HLS Asset: Local URL assigned");
    
    /*
     This delegate callback should only be used to save the location URL
     somewhere in the application. Any additional work should be done in
     `URLSessionTaskDelegate.urlSession(_:task:didCompleteWithError:)`.
     */
    willDownloadToUrlMap[aggregateAssetDownloadTask] = location;
}

/// Method called when a child AVAssetDownloadTask completes.
- (void)URLSession:(NSURLSession *)session
aggregateAssetDownloadTask:(AVAggregateAssetDownloadTask *)aggregateAssetDownloadTask
didCompleteForMediaSelection:(AVMediaSelection *)mediaSelection {
    NSLog(@"HLS Asset: Completed downloading Audio Track");
    /*
     This delegate callback provides an AVMediaSelection object which is now fully available for
     offline use.
     */

    Asset* asset = activeDownloadsMap[aggregateAssetDownloadTask];
    if(asset != nil) {
        aggregateAssetDownloadTask.taskDescription = asset.uniqueId;

        [aggregateAssetDownloadTask resume];
    }
}

/// Method to adopt to subscribe to progress updates of an AVAggregateAssetDownloadTask.
- (void)URLSession:(NSURLSession *)session
aggregateAssetDownloadTask:(AVAggregateAssetDownloadTask *)aggregateAssetDownloadTask
  didLoadTimeRange:(CMTimeRange)timeRange
totalTimeRangesLoaded:(NSArray<NSValue *> *)loadedTimeRanges
timeRangeExpectedToLoad:(CMTimeRange)timeRangeExpectedToLoad
 forMediaSelection:(AVMediaSelection *)mediaSelection {
    // This delegate callback should be used to provide download progress for AVAssetDownloadTask.
    Asset* asset = activeDownloadsMap[aggregateAssetDownloadTask];
    if(asset != nil) {
        Float64 percentComplete = 0.0;
        for (NSValue *value in loadedTimeRanges) {
            CMTimeRange timeRange = [value CMTimeRangeValue];
            percentComplete += CMTimeGetSeconds(timeRange.duration) / CMTimeGetSeconds(timeRangeExpectedToLoad.duration);
        }
        percentComplete *= 100;
        NSLog(@"HLS Asset: Downloading progress: %ld", (long)percentComplete);
    }
}

@end
