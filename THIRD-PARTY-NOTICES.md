# Third-party notices

Cloud Marks is licensed under the MIT License (see `LICENSE`). The Windows app and the Android app include
the third-party software listed below. The browser extensions (Chrome and Firefox) contain only code from this
repository and include no third-party packages. The Windows app, the Android app and the browser extensions use
the Cloud Marks icon, which is made from third-party icons (see "Icon").

- **Windows app**: the libraries are in the `app` folder of the installed folder. The Java runtime in the `runtime`
  folder is the [JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime); its licenses and notices are in
  `runtime\legal`. This file and `LICENSE` are in the installed folder.
- **Android app**: the libraries are in the APK. This file and `LICENSE` are in the `assets` folder of the APK.
- **Browser extensions**: this file and `LICENSE` are in the root folder of each extension (the folder of the Chrome
  extension and the `.xpi` of the Firefox extension).

This list covers the runtime classpath of `:desktopApp` (Windows) and the release runtime classpath of
`:androidApp` (Android) in `apps/cm`. Update it when those dependencies change.

## Icon

The Cloud Marks icon (the files in `assets/icon/` and the icons of the apps and the browser extensions made from
them) is made by combining and modifying the following icons. The original icons have been modified: their shapes
are combined into one image, a background colour is added, and the image is converted and resized for each app.
The authors of the original icons do not endorse Cloud Marks.

- Icons made by [Smashicons](https://www.flaticon.com/authors/smashicons) from
  [www.flaticon.com](https://www.flaticon.com/) are licensed under
  [Creative Commons Attribution 3.0 Unported (CC BY 3.0)](https://creativecommons.org/licenses/by/3.0/)
- Icons made by [Freepik](http://www.freepik.com) from [www.flaticon.com](https://www.flaticon.com/) are licensed under
  [Creative Commons Attribution 3.0 Unported (CC BY 3.0)](https://creativecommons.org/licenses/by/3.0/)

The legal code of CC BY 3.0 is at https://creativecommons.org/licenses/by/3.0/legalcode.

## Components

### Kotlin Standard Library

- Modules: `org.jetbrains.kotlin:kotlin-stdlib` 2.4.21
- License: Apache License 2.0
- Copyright: JetBrains s.r.o. and respective authors and developers
- URL: https://github.com/JetBrains/kotlin
- Included in: Windows app, Android app
- Contains code derived from the ThreeTen backport project (BSD 3-Clause License, see below), from GWT and
  Guava (Apache License 2.0), and from Boost (Boost Software License 1.0).

### kotlinx.coroutines

- Modules: `org.jetbrains.kotlinx:kotlinx-coroutines-core`, `kotlinx-coroutines-slf4j`,
  `kotlinx-coroutines-swing` (Windows app), `kotlinx-coroutines-android` (Android app) 1.11.0
- License: Apache License 2.0
- Copyright: JetBrains s.r.o. and contributors
- URL: https://github.com/Kotlin/kotlinx.coroutines
- Included in: Windows app, Android app

### kotlinx.serialization

- Modules: `org.jetbrains.kotlinx:kotlinx-serialization-core`, `kotlinx-serialization-json` 1.12.0
- License: Apache License 2.0
- Copyright: JetBrains s.r.o. and respective authors and developers
- URL: https://github.com/Kotlin/kotlinx.serialization
- Included in: Windows app, Android app

### kotlinx-datetime

- Modules: `org.jetbrains.kotlinx:kotlinx-datetime` 0.8.0
- License: Apache License 2.0
- Copyright: JetBrains s.r.o. and contributors
- URL: https://github.com/Kotlin/kotlinx-datetime
- Included in: Windows app, Android app
- Contains code derived from the ThreeTen backport project (BSD 3-Clause License, see below).

### kotlinx-io

- Modules: `org.jetbrains.kotlinx:kotlinx-io-core`, `kotlinx-io-bytestring` 0.9.1
- License: Apache License 2.0
- Copyright: JetBrains s.r.o. and respective authors and developers
- URL: https://github.com/Kotlin/kotlinx-io
- Included in: Windows app, Android app

### kotlinx-atomicfu

- Modules: `org.jetbrains.kotlinx:atomicfu` 0.28.0
- License: Apache License 2.0
- Copyright: JetBrains s.r.o. and respective authors and developers
- URL: https://github.com/Kotlin/kotlinx-atomicfu
- Included in: Windows app

### Ktor

- Modules: `io.ktor:ktor-client-core`, `ktor-client-cio` and the modules they depend on (`ktor-http`,
  `ktor-http-cio`, `ktor-io`, `ktor-network`, `ktor-network-tls`, `ktor-utils`, `ktor-events`, `ktor-serialization`,
  `ktor-sse`, `ktor-websockets`, `ktor-websocket-serialization`) 3.6.1
- License: Apache License 2.0
- Copyright: JetBrains s.r.o. and contributors
- URL: https://github.com/ktorio/ktor
- Included in: Windows app, Android app

### Compose Multiplatform

- Modules: `org.jetbrains.compose.runtime`, `org.jetbrains.compose.ui`, `org.jetbrains.compose.foundation`,
  `org.jetbrains.compose.animation`, `org.jetbrains.compose.material` 1.12.2 (`material-ripple` 1.12.0-beta01 in the
  Android app), `org.jetbrains.compose.desktop` 1.12.2 (Windows app), `org.jetbrains.compose.material3:material3`
  1.12.0-alpha03, `org.jetbrains.compose.material3.adaptive:adaptive` 1.3.0,
  `org.jetbrains.compose.material:material-icons-core` 1.7.3, `org.jetbrains.androidx.lifecycle` 2.9.6,
  `org.jetbrains.androidx.savedstate` 1.3.6, `org.jetbrains.androidx.navigationevent` 1.1.0 (Windows app),
  `org.jetbrains.androidx.window:window-core` 1.5.0
- License: Apache License 2.0
- Copyright: JetBrains s.r.o. and respective authors and developers; The Android Open Source Project
- URL: https://github.com/JetBrains/compose-multiplatform, https://github.com/JetBrains/compose-multiplatform-core
- Included in: Windows app, Android app

### AndroidX (including Jetpack Compose)

- Modules in the Windows app: `androidx.annotation` 1.9.1, `androidx.arch.core:core-common` 2.2.0,
  `androidx.collection` 1.5.0, `androidx.compose.runtime` 1.12.1, `androidx.graphics:graphics-shapes` 1.1.0,
  `androidx.lifecycle` 2.11.0, `androidx.navigationevent` 1.1.1, `androidx.savedstate` 1.4.0,
  `androidx.window:window-core` 1.5.0
- Modules in the Android app: `androidx.activity` 1.13.0, `androidx.annotation` 1.10.0
  (`annotation-experimental` 1.5.1), `androidx.arch.core` 2.2.0, `androidx.autofill` 1.0.0, `androidx.collection` 1.5.0,
  `androidx.compose.animation`, `androidx.compose.foundation`, `androidx.compose.runtime`, `androidx.compose.ui`
  1.12.1, `androidx.compose.material:material-icons-core` 1.7.6, `androidx.compose.material:material-ripple`
  1.12.0-beta01, `androidx.compose.material3:material3` 1.5.0-alpha22, `androidx.compose.material3.adaptive` 1.3.0,
  `androidx.concurrent:concurrent-futures` 1.1.0, `androidx.core:core` 1.18.0 (`core-ktx` 1.18.0, `core-viewtree`
  1.0.0), `androidx.customview:customview-poolingcontainer` 1.0.0, `androidx.emoji2` 1.4.0,
  `androidx.graphics:graphics-path` 1.0.1, `androidx.graphics:graphics-shapes` 1.1.0, `androidx.interpolator` 1.0.0,
  `androidx.lifecycle` 2.9.4, `androidx.navigationevent` 1.0.0, `androidx.profileinstaller` 1.4.0,
  `androidx.savedstate` 1.4.0, `androidx.startup:startup-runtime` 1.2.0, `androidx.tracing` 1.2.0,
  `androidx.versionedparcelable` 1.1.1, `androidx.window` 1.5.0
- License: Apache License 2.0
- Copyright: The Android Open Source Project
- URL: https://github.com/androidx/androidx
- Included in: Windows app, Android app

### Guava: ListenableFuture

- Modules: `com.google.guava:listenablefuture` 1.0
- License: Apache License 2.0
- Copyright: The Guava Authors
- URL: https://github.com/google/guava
- Included in: Android app

### Skiko

- Modules: `org.jetbrains.skiko:skiko`, `skiko-awt`, `skiko-awt-runtime-windows-x64` 0.150.2
- License: Apache License 2.0
- Copyright: JetBrains s.r.o.
- URL: https://github.com/JetBrains/skiko
- Included in: Windows app

### Skia

- Built into the native library of Skiko (`app\skiko-windows-x64.dll`)
- License: BSD 3-Clause License (see below)
- Copyright: Copyright (c) 2011 Google Inc.
- URL: https://skia.org/
- Included in: Windows app

Skiko 0.150.2 is built with Skia `m150-a3f509bf52` (https://github.com/JetBrains/skia/releases/tag/m150-a3f509bf52).
The native library of Skiko also contains the following libraries, which Skia uses. The versions are the ones that
the `DEPS` file of that Skia pins.

| Library | License | Copyright | URL |
|---|---|---|---|
| HarfBuzz | MIT License ("Old MIT", see below) | Google, Inc. and others | https://github.com/harfbuzz/harfbuzz |
| ICU 74 (the data is `app\icudtl.dat`) | Unicode License V3 and the licenses of its third-party data (see below) | Unicode, Inc. and others | https://github.com/unicode-org/icu |
| libpng 1.6.56 | PNG Reference Library License version 2 | Cosmin Truta, Glenn Randers-Pehrson, Andreas Dilger, Guy Eric Schalnat and others | https://github.com/pnggroup/libpng |
| zlib 1.3.0.1 | zlib License | Jean-loup Gailly and Mark Adler | https://zlib.net/ |
| Expat 2.7.4 | MIT License (see below) | Thai Open Source Software Center Ltd, Clark Cooper and the Expat maintainers | https://github.com/libexpat/libexpat |
| libjpeg-turbo 3.1.0 | IJG License, BSD 3-Clause License, zlib License (see below) | The libjpeg-turbo Project, Thomas G. Lane, Guido Vollbeding and others | https://github.com/libjpeg-turbo/libjpeg-turbo |
| libwebp | BSD 3-Clause License (see below) | Google Inc. | https://chromium.googlesource.com/webm/libwebp |
| Wuffs | Apache License 2.0 | The Wuffs Authors | https://github.com/google/wuffs |
| SPIRV-Cross | Apache License 2.0 (the sources are dual-licensed under the Apache License 2.0 or the MIT License). The SPIR-V headers it includes are under the licenses of The Khronos Group (see below) | Arm Limited, Robert Konrad, The Khronos Group Inc. and others | https://github.com/KhronosGroup/SPIRV-Cross |
| D3D12 Memory Allocator | MIT License (see below) | Advanced Micro Devices, Inc. | https://github.com/GPUOpen-LibrariesAndSDKs/D3D12MemoryAllocator |

This software is based in part on the work of the Independent JPEG Group.

### JetBrains Runtime API

- Modules: `org.jetbrains.runtime:jbr-api` 1.9.0
- License: Apache License 2.0
- Copyright: JetBrains s.r.o.
- URL: https://github.com/JetBrains/JetBrainsRuntimeApi
- Included in: Windows app

### JetBrains Java Annotations

- Modules: `org.jetbrains:annotations` 23.0.0
- License: Apache License 2.0
- Copyright: JetBrains s.r.o.
- URL: https://github.com/JetBrains/java-annotations
- Included in: Windows app, Android app

### JSpecify

- Modules: `org.jspecify:jspecify` 1.0.0
- License: Apache License 2.0
- Copyright: The JSpecify Authors
- URL: https://jspecify.dev/
- Included in: Windows app, Android app

### AWS SDK for Kotlin

- Modules: `aws.sdk.kotlin:s3`, `aws-config`, `aws-core`, `aws-endpoint`, `aws-http` 1.9.37
- License: Apache License 2.0
- Copyright: Amazon.com, Inc. or its affiliates
- URL: https://github.com/awslabs/aws-sdk-kotlin
- Included in: Windows app, Android app

### Smithy Kotlin

- Modules: `aws.smithy.kotlin:*` 1.7.15 (the runtime of the AWS SDK for Kotlin)
- License: Apache License 2.0
- Copyright: Amazon.com, Inc. or its affiliates
- URL: https://github.com/smithy-lang/smithy-kotlin
- Included in: Windows app, Android app

### OkHttp

- Modules: `com.squareup.okhttp3:okhttp`, `okhttp-coroutines` 5.5.0
- License: Apache License 2.0
- Copyright: Square, Inc.
- URL: https://github.com/square/okhttp
- Included in: Windows app, Android app
- Contains a copy of the Public Suffix List (see below).

### Okio

- Modules: `com.squareup.okio:okio` 3.18.2
- License: Apache License 2.0
- Copyright: Square, Inc.
- URL: https://github.com/square/okio
- Included in: Windows app, Android app

### Public Suffix List

- Included by OkHttp as `PublicSuffixDatabase.list`
- License: Mozilla Public License 2.0. The source is available at https://publicsuffix.org/list/.
- URL: https://publicsuffix.org/
- Included in: Windows app, Android app

```text
This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
```

### SLF4J API

- Modules: `org.slf4j:slf4j-api` 2.0.20
- License: MIT License (see below)
- Copyright: Copyright (c) 2004-2023 QOS.ch
- URL: https://www.slf4j.org/
- Included in: Windows app, Android app

## Notices

The following are the NOTICE files of the components above that are licensed under the Apache License 2.0.

### Kotlin

```text
   =========================================================================
   ==  NOTICE file corresponding to the section 4 d of                    ==
   ==  the Apache License, Version 2.0,                                   ==
   ==  in this case for the Kotlin Compiler distribution.                 ==
   =========================================================================

   Kotlin Compiler
   Copyright 2010-2024 JetBrains s.r.o and respective authors and developers
```

### kotlinx.coroutines

```text
=========================================================================
==  NOTICE file corresponding to the section 4 d of                    ==
==  the Apache License, Version 2.0,                                   ==
==  in this case for the kotlinx.coroutines library.                   ==
=========================================================================

kotlinx.coroutines library.
Copyright 2016-2025 JetBrains s.r.o and contributors
```

### kotlinx.serialization

```text
   =========================================================================
   ==  NOTICE file corresponding to the section 4 d of                    ==
   ==  the Apache License, Version 2.0,                                   ==
   ==  in this case for the kotlix.serialization library.                 ==
   =========================================================================

   kotlinx.serialization library.
   Copyright 2017-2019 JetBrains s.r.o and respective authors and developers
```

### kotlinx-io

```text
   =========================================================================
   ==  NOTICE file corresponding to the section 4 d of                    ==
   ==  the Apache License, Version 2.0,                                   ==
   ==  in this case for the kotlinx-io library.                            ==
   =========================================================================

   kotlinx-io library.
   Copyright 2017-2023 JetBrains s.r.o and respective authors and developers
```

### kotlinx-atomicfu

```text
   =========================================================================
   ==  NOTICE file corresponding to the section 4 d of                    ==
   ==  the Apache License, Version 2.0,                                   ==
   ==  in this case for the kotlix.atomicfu library.                      ==
   =========================================================================

   kotlinx.atoimcfu library.
   Copyright 2017 JetBrains s.r.o and respective authors and developers
```

### Skiko

```text
This project contains code adapted from "The Android Open Source Project" licensed under the Apache License, Version 2.0 (the "License"):

https://android.googlesource.com/platform/frameworks/base
```

### AWS SDK for Kotlin

```text
aws-sdk-kotlin
Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
```

### Smithy Kotlin

```text
AWS SDK for Kotlin
Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.


This software includes a copy of the CRC32C implementation (runtime/runtime-core/common/src/aws/smithy/kotlin/runtime/hashing/Crc32c.kt)
from the Apache Hadoop project (https://github.com/apache/hadoop).
Copyright 2006 and onwards The Apache Software Foundation.


This software includes a copy of async primitives (runtime/runtime-core/common/src/aws/smithy/kotlin/runtime/io/internal/AwaitingSlot.kt)
from the Ktor project (https://github.com/ktorio/ktor).
Copyright 2014-2021 JetBrains s.r.o and contributors.
```

## License texts

### Apache License 2.0

```text
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS
```

### MIT License (SLF4J API)

```text
Copyright (c) 2004-2023 QOS.ch
All rights reserved.

Permission is hereby granted, free  of charge, to any person obtaining
a  copy  of this  software  and  associated  documentation files  (the
"Software"), to  deal in  the Software without  restriction, including
without limitation  the rights to  use, copy, modify,  merge, publish,
distribute,  sublicense, and/or sell  copies of  the Software,  and to
permit persons to whom the Software  is furnished to do so, subject to
the following conditions:

The  above  copyright  notice  and  this permission  notice  shall  be
included in all copies or substantial portions of the Software.

THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### BSD 3-Clause License (Skia)

```text
Copyright (c) 2011 Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

  * Neither the name of the copyright holder nor the names of its
    contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### BSD 3-Clause License (ThreeTen backport, in the Kotlin Standard Library and kotlinx-datetime)

```text
Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos.

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice,
  this list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of JSR-310 nor the names of its contributors
  may be used to endorse or promote products derived from this software
  without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### MIT License ("Old MIT", HarfBuzz)

```text
HarfBuzz is licensed under the so-called "Old MIT" license.  Details follow.
For parts of HarfBuzz that are licensed under different licenses see individual
files names COPYING in subdirectories where applicable.

Copyright © 2010-2022  Google, Inc.
Copyright © 2015-2020  Ebrahim Byagowi
Copyright © 2019,2020  Facebook, Inc.
Copyright © 2012,2015  Mozilla Foundation
Copyright © 2011  Codethink Limited
Copyright © 2008,2010  Nokia Corporation and/or its subsidiary(-ies)
Copyright © 2009  Keith Stribley
Copyright © 2011  Martin Hosken and SIL International
Copyright © 2007  Chris Wilson
Copyright © 2005,2006,2020,2021,2022,2023  Behdad Esfahbod
Copyright © 2004,2007,2008,2009,2010,2013,2021,2022,2023  Red Hat, Inc.
Copyright © 1998-2005  David Turner and Werner Lemberg
Copyright © 2016  Igalia S.L.
Copyright © 2022  Matthias Clasen
Copyright © 2018,2021  Khaled Hosny
Copyright © 2018,2019,2020  Adobe, Inc
Copyright © 2013-2015  Alexei Podtelezhnikov

For full copyright notices consult the individual files in the package.


Permission is hereby granted, without written agreement and without
license or royalty fees, to use, copy, modify, and distribute this
software and its documentation for any purpose, provided that the
above copyright notice and the following two paragraphs appear in
all copies of this software.

IN NO EVENT SHALL THE COPYRIGHT HOLDER BE LIABLE TO ANY PARTY FOR
DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES
ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN
IF THE COPYRIGHT HOLDER HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
DAMAGE.

THE COPYRIGHT HOLDER SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING,
BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE.  THE SOFTWARE PROVIDED HEREUNDER IS
ON AN "AS IS" BASIS, AND THE COPYRIGHT HOLDER HAS NO OBLIGATION TO
PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.
```

### Unicode License V3 and third-party licenses (ICU)

```text
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 2016-2023 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.

----------------------------------------------------------------------

Third-Party Software Licenses

This section contains third-party software notices and/or additional
terms for licensed third-party software components included within ICU
libraries.

----------------------------------------------------------------------

ICU License - ICU 1.8.1 to ICU 57.1

COPYRIGHT AND PERMISSION NOTICE

Copyright (c) 1995-2016 International Business Machines Corporation and others
All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, and/or sell copies of the Software, and to permit persons
to whom the Software is furnished to do so, provided that the above
copyright notice(s) and this permission notice appear in all copies of
the Software and that both the above copyright notice(s) and this
permission notice appear in supporting documentation.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF THIRD PARTY RIGHTS. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
HOLDERS INCLUDED IN THIS NOTICE BE LIABLE FOR ANY CLAIM, OR ANY
SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES, OR ANY DAMAGES WHATSOEVER
RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF
CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

Except as contained in this notice, the name of a copyright holder
shall not be used in advertising or otherwise to promote the sale, use
or other dealings in this Software without prior written authorization
of the copyright holder.

All trademarks and registered trademarks mentioned herein are the
property of their respective owners.

----------------------------------------------------------------------

Chinese/Japanese Word Break Dictionary Data (cjdict.txt)

 #     The Google Chrome software developed by Google is licensed under
 # the BSD license. Other software included in this distribution is
 # provided under other licenses, as set forth below.
 #
 #  The BSD License
 #  http://opensource.org/licenses/bsd-license.php
 #  Copyright (C) 2006-2008, Google Inc.
 #
 #  All rights reserved.
 #
 #  Redistribution and use in source and binary forms, with or without
 # modification, are permitted provided that the following conditions are met:
 #
 #  Redistributions of source code must retain the above copyright notice,
 # this list of conditions and the following disclaimer.
 #  Redistributions in binary form must reproduce the above
 # copyright notice, this list of conditions and the following
 # disclaimer in the documentation and/or other materials provided with
 # the distribution.
 #  Neither the name of  Google Inc. nor the names of its
 # contributors may be used to endorse or promote products derived from
 # this software without specific prior written permission.
 #
 #
 #  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 # CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 # INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 # MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 # DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 # LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 # CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 # SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 # BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 # LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 # NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 # SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 #
 #
 #  The word list in cjdict.txt are generated by combining three word lists
 # listed below with further processing for compound word breaking. The
 # frequency is generated with an iterative training against Google web
 # corpora.
 #
 #  * Libtabe (Chinese)
 #    - https://sourceforge.net/project/?group_id=1519
 #    - Its license terms and conditions are shown below.
 #
 #  * IPADIC (Japanese)
 #    - http://chasen.aist-nara.ac.jp/chasen/distribution.html
 #    - Its license terms and conditions are shown below.
 #
 #  ---------COPYING.libtabe ---- BEGIN--------------------
 #
 #  /*
 #   * Copyright (c) 1999 TaBE Project.
 #   * Copyright (c) 1999 Pai-Hsiang Hsiao.
 #   * All rights reserved.
 #   *
 #   * Redistribution and use in source and binary forms, with or without
 #   * modification, are permitted provided that the following conditions
 #   * are met:
 #   *
 #   * . Redistributions of source code must retain the above copyright
 #   *   notice, this list of conditions and the following disclaimer.
 #   * . Redistributions in binary form must reproduce the above copyright
 #   *   notice, this list of conditions and the following disclaimer in
 #   *   the documentation and/or other materials provided with the
 #   *   distribution.
 #   * . Neither the name of the TaBE Project nor the names of its
 #   *   contributors may be used to endorse or promote products derived
 #   *   from this software without specific prior written permission.
 #   *
 #   * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 #   * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 #   * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 #   * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 #   * REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 #   * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 #   * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 #   * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 #   * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 #   * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 #   * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 #   * OF THE POSSIBILITY OF SUCH DAMAGE.
 #   */
 #
 #  /*
 #   * Copyright (c) 1999 Computer Systems and Communication Lab,
 #   *                    Institute of Information Science, Academia
 #       *                    Sinica. All rights reserved.
 #   *
 #   * Redistribution and use in source and binary forms, with or without
 #   * modification, are permitted provided that the following conditions
 #   * are met:
 #   *
 #   * . Redistributions of source code must retain the above copyright
 #   *   notice, this list of conditions and the following disclaimer.
 #   * . Redistributions in binary form must reproduce the above copyright
 #   *   notice, this list of conditions and the following disclaimer in
 #   *   the documentation and/or other materials provided with the
 #   *   distribution.
 #   * . Neither the name of the Computer Systems and Communication Lab
 #   *   nor the names of its contributors may be used to endorse or
 #   *   promote products derived from this software without specific
 #   *   prior written permission.
 #   *
 #   * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 #   * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 #   * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 #   * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 #   * REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 #   * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 #   * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 #   * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 #   * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 #   * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 #   * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 #   * OF THE POSSIBILITY OF SUCH DAMAGE.
 #   */
 #
 #  Copyright 1996 Chih-Hao Tsai @ Beckman Institute,
 #      University of Illinois
 #  c-tsai4@uiuc.edu  http://casper.beckman.uiuc.edu/~c-tsai4
 #
 #  ---------------COPYING.libtabe-----END--------------------------------
 #
 #
 #  ---------------COPYING.ipadic-----BEGIN-------------------------------
 #
 #  Copyright 2000, 2001, 2002, 2003 Nara Institute of Science
 #  and Technology.  All Rights Reserved.
 #
 #  Use, reproduction, and distribution of this software is permitted.
 #  Any copy of this software, whether in its original form or modified,
 #  must include both the above copyright notice and the following
 #  paragraphs.
 #
 #  Nara Institute of Science and Technology (NAIST),
 #  the copyright holders, disclaims all warranties with regard to this
 #  software, including all implied warranties of merchantability and
 #  fitness, in no event shall NAIST be liable for
 #  any special, indirect or consequential damages or any damages
 #  whatsoever resulting from loss of use, data or profits, whether in an
 #  action of contract, negligence or other tortuous action, arising out
 #  of or in connection with the use or performance of this software.
 #
 #  A large portion of the dictionary entries
 #  originate from ICOT Free Software.  The following conditions for ICOT
 #  Free Software applies to the current dictionary as well.
 #
 #  Each User may also freely distribute the Program, whether in its
 #  original form or modified, to any third party or parties, PROVIDED
 #  that the provisions of Section 3 ("NO WARRANTY") will ALWAYS appear
 #  on, or be attached to, the Program, which is distributed substantially
 #  in the same form as set out herein and that such intended
 #  distribution, if actually made, will neither violate or otherwise
 #  contravene any of the laws and regulations of the countries having
 #  jurisdiction over the User or the intended distribution itself.
 #
 #  NO WARRANTY
 #
 #  The program was produced on an experimental basis in the course of the
 #  research and development conducted during the project and is provided
 #  to users as so produced on an experimental basis.  Accordingly, the
 #  program is provided without any warranty whatsoever, whether express,
 #  implied, statutory or otherwise.  The term "warranty" used herein
 #  includes, but is not limited to, any warranty of the quality,
 #  performance, merchantability and fitness for a particular purpose of
 #  the program and the nonexistence of any infringement or violation of
 #  any right of any third party.
 #
 #  Each user of the program will agree and understand, and be deemed to
 #  have agreed and understood, that there is no warranty whatsoever for
 #  the program and, accordingly, the entire risk arising from or
 #  otherwise connected with the program is assumed by the user.
 #
 #  Therefore, neither ICOT, the copyright holder, or any other
 #  organization that participated in or was otherwise related to the
 #  development of the program and their respective officials, directors,
 #  officers and other employees shall be held liable for any and all
 #  damages, including, without limitation, general, special, incidental
 #  and consequential damages, arising out of or otherwise in connection
 #  with the use or inability to use the program or any product, material
 #  or result produced or otherwise obtained by using the program,
 #  regardless of whether they have been advised of, or otherwise had
 #  knowledge of, the possibility of such damages at any time during the
 #  project or thereafter.  Each user will be deemed to have agreed to the
 #  foregoing by his or her commencement of use of the program.  The term
 #  "use" as used herein includes, but is not limited to, the use,
 #  modification, copying and distribution of the program and the
 #  production of secondary products from the program.
 #
 #  In the case where the program, whether in its original form or
 #  modified, was distributed or delivered to or received by a user from
 #  any person, organization or entity other than ICOT, unless it makes or
 #  grants independently of ICOT any specific warranty to the user in
 #  writing, such person, organization or entity, will also be exempted
 #  from and not be held liable to the user for any such damages as noted
 #  above as far as the program is concerned.
 #
 #  ---------------COPYING.ipadic-----END----------------------------------

----------------------------------------------------------------------

Lao Word Break Dictionary Data (laodict.txt)

 # Copyright (C) 2016 and later: Unicode, Inc. and others.
 # License & terms of use: http://www.unicode.org/copyright.html
 # Copyright (c) 2015 International Business Machines Corporation
 # and others. All Rights Reserved.
 #
 # Project: https://github.com/rober42539/lao-dictionary
 # Dictionary: https://github.com/rober42539/lao-dictionary/laodict.txt
 # License: https://github.com/rober42539/lao-dictionary/LICENSE.txt
 #          (copied below)
 #
 #	This file is derived from the above dictionary version of Nov 22, 2020
 #  ----------------------------------------------------------------------
 #  Copyright (C) 2013 Brian Eugene Wilson, Robert Martin Campbell.
 #  All rights reserved.
 #
 #  Redistribution and use in source and binary forms, with or without
 #  modification, are permitted provided that the following conditions are met:
 #
 #  Redistributions of source code must retain the above copyright notice, this
 #  list of conditions and the following disclaimer. Redistributions in binary
 #  form must reproduce the above copyright notice, this list of conditions and
 #  the following disclaimer in the documentation and/or other materials
 #  provided with the distribution.
 #
 # THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 # "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 # LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 # FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 # COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 # INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 # (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 # SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 # HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 # STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 # ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 # OF THE POSSIBILITY OF SUCH DAMAGE.
 #  --------------------------------------------------------------------------

----------------------------------------------------------------------

Burmese Word Break Dictionary Data (burmesedict.txt)

 #  Copyright (c) 2014 International Business Machines Corporation
 #  and others. All Rights Reserved.
 #
 #  This list is part of a project hosted at:
 #    github.com/kanyawtech/myanmar-karen-word-lists
 #
 #  --------------------------------------------------------------------------
 #  Copyright (c) 2013, LeRoy Benjamin Sharon
 #  All rights reserved.
 #
 #  Redistribution and use in source and binary forms, with or without
 #  modification, are permitted provided that the following conditions
 #  are met: Redistributions of source code must retain the above
 #  copyright notice, this list of conditions and the following
 #  disclaimer.  Redistributions in binary form must reproduce the
 #  above copyright notice, this list of conditions and the following
 #  disclaimer in the documentation and/or other materials provided
 #  with the distribution.
 #
 #    Neither the name Myanmar Karen Word Lists, nor the names of its
 #    contributors may be used to endorse or promote products derived
 #    from this software without specific prior written permission.
 #
 #  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 #  CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 #  INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 #  MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 #  DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS
 #  BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 #  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 #  TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 #  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 #  ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 #  TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
 #  THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 #  SUCH DAMAGE.
 #  --------------------------------------------------------------------------

----------------------------------------------------------------------

Time Zone Database

  ICU uses the public domain data and code derived from Time Zone
Database for its time zone support. The ownership of the TZ database
is explained in BCP 175: Procedure for Maintaining the Time Zone
Database section 7.

 # 7.  Database Ownership
 #
 #    The TZ database itself is not an IETF Contribution or an IETF
 #    document.  Rather it is a pre-existing and regularly updated work
 #    that is in the public domain, and is intended to remain in the
 #    public domain.  Therefore, BCPs 78 [RFC5378] and 79 [RFC3979] do
 #    not apply to the TZ Database or contributions that individuals make
 #    to it.  Should any claims be made and substantiated against the TZ
 #    Database, the organization that is providing the IANA
 #    Considerations defined in this RFC, under the memorandum of
 #    understanding with the IETF, currently ICANN, may act in accordance
 #    with all competent court orders.  No ownership claims will be made
 #    by ICANN or the IETF Trust on the database or the code.  Any person
 #    making a contribution to the database or code waives all rights to
 #    future claims in that contribution or in the TZ Database.

----------------------------------------------------------------------

Google double-conversion

Copyright 2006-2011, the V8 project authors. All rights reserved.
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.
    * Neither the name of Google Inc. nor the names of its
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

----------------------------------------------------------------------

File: aclocal.m4 (only for ICU4C)
Section: pkg.m4 - Macros to locate and utilise pkg-config.


Copyright © 2004 Scott James Remnant <scott@netsplit.com>.
Copyright © 2012-2015 Dan Nicholson <dbn.lists@gmail.com>

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA
02111-1307, USA.

As a special exception to the GNU General Public License, if you
distribute this file as part of a program that contains a
configuration script generated by Autoconf, you may include it under
the same distribution terms that you use for the rest of that
program.


(The condition for the exception is fulfilled because
ICU4C includes a configuration script generated by Autoconf,
namely the `configure` script.)

----------------------------------------------------------------------

File: config.guess (only for ICU4C)


This file is free software; you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, see <https://www.gnu.org/licenses/>.

As a special exception to the GNU General Public License, if you
distribute this file as part of a program that contains a
configuration script generated by Autoconf, you may include it under
the same distribution terms that you use for the rest of that
program.  This Exception is an additional permission under section 7
of the GNU General Public License, version 3 ("GPLv3").


(The condition for the exception is fulfilled because
ICU4C includes a configuration script generated by Autoconf,
namely the `configure` script.)

----------------------------------------------------------------------

File: install-sh (only for ICU4C)


Copyright 1991 by the Massachusetts Institute of Technology

Permission to use, copy, modify, distribute, and sell this software and its
documentation for any purpose is hereby granted without fee, provided that
the above copyright notice appear in all copies and that both that
copyright notice and this permission notice appear in supporting
documentation, and that the name of M.I.T. not be used in advertising or
publicity pertaining to distribution of the software without specific,
written prior permission.  M.I.T. makes no representations about the
suitability of this software for any purpose.  It is provided "as is"
without express or implied warranty.
```

### MIT License (Expat)

```text
Copyright (c) 1998-2000 Thai Open Source Software Center Ltd and Clark Cooper
Copyright (c) 2001-2025 Expat maintainers

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### IJG License (libjpeg-turbo)

The "LEGAL ISSUES" section of `README.ijg` of libjpeg-turbo.

```text
LEGAL ISSUES
============

In plain English:

1. We don't promise that this software works.  (But if you find any bugs,
   please let us know!)
2. You can use this software for whatever you want.  You don't have to pay us.
3. You may not pretend that you wrote this software.  If you use it in a
   program, you must acknowledge somewhere in your documentation that
   you've used the IJG code.

In legalese:

The authors make NO WARRANTY or representation, either express or implied,
with respect to this software, its quality, accuracy, merchantability, or
fitness for a particular purpose.  This software is provided "AS IS", and you,
its user, assume the entire risk as to its quality and accuracy.

This software is copyright (C) 1991-2020, Thomas G. Lane, Guido Vollbeding.
All Rights Reserved except as specified below.

Permission is hereby granted to use, copy, modify, and distribute this
software (or portions thereof) for any purpose, without fee, subject to these
conditions:
(1) If any part of the source code for this software is distributed, then this
README file must be included, with this copyright and no-warranty notice
unaltered; and any additions, deletions, or changes to the original files
must be clearly indicated in accompanying documentation.
(2) If only executable code is distributed, then the accompanying
documentation must state that "this software is based in part on the work of
the Independent JPEG Group".
(3) Permission for use of this software is granted only if the user accepts
full responsibility for any undesirable consequences; the authors accept
NO LIABILITY for damages of any kind.

These conditions apply to any software derived from or based on the IJG code,
not just to the unmodified library.  If you use our work, you ought to
acknowledge us.

Permission is NOT granted for the use of any IJG author's name or company name
in advertising or publicity relating to this software or products derived from
it.  This software may be referred to only as "the Independent JPEG Group's
software".

We specifically permit and encourage the use of this software as the basis of
commercial products, provided that all warranty or liability claims are
assumed by the product vendor.
```

### BSD 3-Clause License (libjpeg-turbo)

The "The Modified (3-clause) BSD License" section of `LICENSE.md` of libjpeg-turbo.

```text
The Modified (3-clause) BSD License
===================================

Copyright (C)2009-2024 D. R. Commander.  All Rights Reserved.<br>
Copyright (C)2015 Viktor Szathmáry.  All Rights Reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice,
  this list of conditions and the following disclaimer.
- Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.
- Neither the name of the libjpeg-turbo Project nor the names of its
  contributors may be used to endorse or promote products derived from this
  software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS",
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

### BSD 3-Clause License (libwebp)

```text
Copyright (c) 2010, Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

  * Neither the name of Google nor the names of its contributors may
    be used to endorse or promote products derived from this software
    without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Khronos license (`spirv.hpp` in SPIRV-Cross)

SPIRV-Cross includes the SPIR-V header `spirv.hpp` of The Khronos Group. The following is
`LICENSES/LicenseRef-KhronosFreeUse.txt` of SPIRV-Cross, which is the notice at the top of `spirv.hpp`.

```text
Copyright (c) 2014-2020 The Khronos Group Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and/or associated documentation files (the "Materials"),
to deal in the Materials without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Materials, and to permit persons to whom the
Materials are furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Materials.

MODIFICATIONS TO THIS FILE MAY MEAN IT NO LONGER ACCURATELY REFLECTS KHRONOS
STANDARDS. THE UNMODIFIED, NORMATIVE VERSIONS OF KHRONOS SPECIFICATIONS AND
HEADER INFORMATION ARE LOCATED AT https://www.khronos.org/registry/

THE MATERIALS ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM,OUT OF OR IN CONNECTION WITH THE MATERIALS OR THE USE OR OTHER DEALINGS
IN THE MATERIALS.
```

### MIT License (`GLSL.std.450.h` in SPIRV-Cross)

SPIRV-Cross also includes the header `GLSL.std.450.h` of The Khronos Group. The following are the notice at the top
of `GLSL.std.450.h` and `LICENSES/MIT.txt` of SPIRV-Cross, which the notice refers to.

```text
/*
 * Copyright 2014-2016,2021 The Khronos Group, Inc.
 * SPDX-License-Identifier: MIT
 *
 * MODIFICATIONS TO THIS FILE MAY MEAN IT NO LONGER ACCURATELY REFLECTS KHRONOS
 * STANDARDS. THE UNMODIFIED, NORMATIVE VERSIONS OF KHRONOS SPECIFICATIONS AND
 * HEADER INFORMATION ARE LOCATED AT https://www.khronos.org/registry/
*/
```

```text
MIT License Copyright (c) <year> <copyright holders>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is furnished
to do so, subject to the following conditions:

The above copyright notice and this permission notice (including the next
paragraph) shall be included in all copies or substantial portions of the
Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF
OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### MIT License (D3D12 Memory Allocator)

```text
Copyright (c) 2019-2020 Advanced Micro Devices, Inc. All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```
