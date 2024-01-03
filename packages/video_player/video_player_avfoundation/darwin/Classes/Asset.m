#import "Asset.h"

@implementation Asset
 
- (instancetype)initWithURLAsset:(AVURLAsset *)urlAsset {
    self = [super init];
    if (self) {
        _urlAsset = urlAsset;
        _uniqueId = urlAsset.URL.relativePath;
    }
    return self;
}
 
@end
