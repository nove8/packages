#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>

#import "Asset.h"

@interface AssetPersistenceManager : NSObject<AVAssetDownloadDelegate>
/// Bool used to track if the AssetPersistenceManager finished restoring its state.
@property bool didRestorePersistenceManager;

+ (AssetPersistenceManager *)sharedManager;

- (void)restorePersistenceManager;
- (void)downloadStream:(Asset *)asset streamName:(NSString *)streamName audioTrackName:(NSString *)audioTrackName;
- (Asset *)assetForStream:(NSString *)uniqueId;
- (Asset *)localAssetForStream:(NSString *)uniqueId;
- (AssetDownloadState)downloadState:(Asset *)asset audioTrackName:(NSString *)audioTrackName;
- (void)deleteAsset:(Asset *)asset;
- (void)cancelDownload:(Asset *)asset;
@end
