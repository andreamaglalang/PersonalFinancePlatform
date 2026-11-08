# PersonalFinanceAndroid

Initial ELECT3/MOBDEV Personal Finance and Budget Monitoring Platform Android scaffold. The single Compose activity displays a static welcome screen only.

## Configuration

- Namespace and application ID: `com.elect3.mobdev.personalfinance`.
- Minimum SDK 26; compile/target SDK 36; Build Tools 36.0.0.
- Gradle 9.1.0 all distribution; AGP 9.0.1 with built-in Kotlin 2.2.10.
- Compose compiler plugin 2.2.10; Compose BOM 2025.08.00.
- Activity Compose 1.10.1; Lifecycle ViewModel Compose and Runtime Compose 2.9.2; Coroutines Android 1.10.2.
- Java and Kotlin bytecode target 17. JDK 17 is the proposed Gradle runtime; a usable local installation has not been verified. No automatic JDK provisioning is configured.

Dependency versions are declared in `gradle/libs.versions.toml`. AGP supplies Kotlin support; the standalone `org.jetbrains.kotlin.android` plugin is intentionally absent.

## Scope and verification

Dependency resolution, compilation, lint, tests, and runtime behavior remain unverified. No Gradle sync, wrapper execution, build, application launch, or dependency download was performed to create this scaffold. These actions require separate approval.

There is no network permission, API configuration, authentication, transaction implementation, or database. MVVM, StateFlow, Coroutines-based behavior, and Room persistence are future work; dependency declarations alone do not implement those features. Room, KSP, and Retrofit are not included.

Do not commit machine-specific `local.properties`, credentials, signing material, or generated output. Existing repository ignore rules cover these local artifacts. This Android project is separate from the .NET solution.

## Wrapper provenance

The wrapper scripts were copied byte-for-byte from previously verified temporary downloads of the official `gradle/gradle` repository at tag `v9.1.0`, resolved to commit `e45a8dbf2470c2e2474ccc25be9f49331406a07e`:

- `https://raw.githubusercontent.com/gradle/gradle/v9.1.0/gradlew`
- `https://raw.githubusercontent.com/gradle/gradle/v9.1.0/gradlew.bat`

SHA-256 values:

```text
gradlew: b2fe376b143a459ba5d0bd290dc89beed5399fc6d159cd1214bd642ea94bcf07
gradlew.bat: 9386e790d58b9368ca8e034536a5baa688643d51cb37bfa462503d36fd0291a6
gradle-wrapper.jar: 76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3
gradle-9.1.0-all.zip: b84e04fa845fecba48551f425957641074fcc00a88a84d2aae5808743b35fc85
```

The wrapper JAR came from the checksum-verified local Gradle 9.1.0 distribution. The distribution checksum is pinned in wrapper properties. Wrapper files were not executed.

Backend migration `20261107054048_AddBillPaymentReceiptsAndGuards` remains **UNSAFE TO APPLY**. Android scaffolding does not authorize backend or database operations.
