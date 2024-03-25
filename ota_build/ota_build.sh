#!/bin/bash

if [ $# -eq 0 ] ;then
    echo "No arguments supplied"
    exit 1
fi

starting_dir=$PWD
trap "cd $starting_dir; exit 1" INT

# Get current cpu cores number and set it as default number of threads for make
CPUS=$(nproc)

# source and lunch the product again, to start from a clean state
# First parameter of this script should be the full parameter of the lunch command
source build/envsetup.sh

if [ $? != 0 ] ;then
    echo "Failed to source"
    exit 1
fi

lunch $1

if [ $? != 0 ] ;then
    echo "Failed to lunch"
    exit 1
fi

# Start from a clean product directory
m installclean -j$CPUS

if [ $? != 0 ]; then
    echo "installclean failed"
    exit 1
fi

# Build Android
m -j$CPUS

if [ $? != 0 ]; then
    echo "make failed"
    exit 1
fi

# Remove the ota_custom directory, if it exists
rm -rf $OUT/ota_custom

mkdir $OUT/ota_custom
# Copy the sample OTA archive contents
cp -r $ANDROID_BUILD_TOP/vendor/rs/ota_build/$TARGET_PRODUCT/ota_sample/* $OUT/ota_custom/

if [ $? != 0 ]; then
    echo "Cannot copy sample packages to out directory"
    exit 1
fi

cd $OUT

files=(
    "ramdisk/system/etc/ramdisk/build.prop"
    "system/build.prop"
    "system/product/etc/build.prop"
    "system/system_dlkm/etc/build.prop"
    "system/system_ext/etc/build.prop"
    "vendor/vendor_dlkm/etc/build.prop"
    "vendor/odm_dlkm/etc/build.prop"
    "vendor/odm/etc/build.prop"
    "vendor/build.prop"
)

# Replace test-keys with release-keys
# This is usually managed by the 'sign_target_files_apks' python script, but
# that script is quite slow and we don't need anything else, so just use sed should
# be enough
for file in "${files[@]}"; do
    sed -i 's/test-keys/release-keys/g' "$file"
done

# Recreate the vendor image, do not build it again
m vnod -j$CPUS

if [ $? != 0 ]; then
    echo "vnod failed"
    exit 1
fi

# Recreate the system image, do not build it again
m snod -j$CPUS

if [ $? != 0 ]; then
    echo "snod failed"
    exit 1
fi

# Recreate the boot image
m bootimage -j$CPUS

if [ $? != 0 ]; then
    echo "make bootimage failed"
    exit 1
fi

# Convert the sparse system image to raw
simg2img system.img system.raw

if [ $? != 0 ]; then
    echo "simg2img system failed"
    exit 1
fi

# Convert the sparse vendor image to raw
simg2img vendor.img vendor.raw

if [ $? != 0 ]; then
    echo "simg2img vendor failed"
    exit 1
fi

# Move the OS images to the ota_custom directory, where the OTA zip contents are
mv system.raw $OUT/ota_custom/system.img
mv vendor.raw $OUT/ota_custom/vendor.img
cp boot.img $OUT/ota_custom/boot.img

cd $OUT/ota_custom

# Create the OTA zip
zip -r ${TARGET_PRODUCT}_ota.zip *

if [ $? != 0 ]; then
    echo "Cannot compress final zip"
    exit 1
fi

cd $OUT

# Sign the OTA zip with the AOSP key
java -jar -Djava.library.path="$ANDROID_BUILD_TOP/out/host/linux-x86/lib64" $ANDROID_BUILD_TOP/out/host/linux-x86/framework/signapk.jar -w $ANDROID_BUILD_TOP/vendor/rs/config/security/releasekey.x509.pem $ANDROID_BUILD_TOP/vendor/rs/config/security/releasekey.pk8 $OUT/ota_custom/${TARGET_PRODUCT}_ota.zip $OUT/${TARGET_PRODUCT}_ota.zip

if [ $? != 0 ]; then
    echo "Cannot sign final zip"
    exit 1
fi

# Removed the unsigned zip to save some space
rm -rf $OUT/ota_custom/${TARGET_PRODUCT}_ota.zip

cd $ANDROID_BUILD_TOP
