/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.appsearch.app;

import androidx.annotation.NonNull;
import androidx.core.util.ObjectsCompat;
import androidx.core.util.Preconditions;

import java.util.Arrays;

/** This class represents a uniquely identifiable package. */
public class PackageIdentifier {
    private final String mPackageName;
    private final byte[] mSha256Certificate;

    /**
     * Creates a unique identifier for a package.
     *
     * @param packageName Name of the package.
     * @param sha256Certificate SHA256 certificate digest of the package.
     */
    public PackageIdentifier(@NonNull String packageName, @NonNull byte[] sha256Certificate) {
        mPackageName = Preconditions.checkNotNull(packageName);
        mSha256Certificate = Preconditions.checkNotNull(sha256Certificate);
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    @NonNull
    public byte[] getSha256Certificate() {
        return mSha256Certificate;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || !(obj instanceof PackageIdentifier)) {
            return false;
        }
        final PackageIdentifier other = (PackageIdentifier) obj;
        return this.mPackageName.equals(other.mPackageName)
                && Arrays.equals(this.mSha256Certificate, other.mSha256Certificate);
    }

    @Override
    public int hashCode() {
        return ObjectsCompat.hash(mPackageName, Arrays.hashCode(mSha256Certificate));
    }
}
