================================================================================
================================================================================

              Third-Party Software for dpn-rest-federator-gateway

--------------------------------------------------------------------------------

The following 3rd-party software packages may be used by or distributed with dpn-rest-federator-gateway. Any information relevant to third-party vendors listed below are collected using common, reasonable means.

Date generated: 2026-9-23

Revision ID: 3b70a3d90f88ab2280d8a853388c4cdaaf4ac8b1

================================================================================
================================================================================



================================================================================

                                  Dependencies

================================================================================

- Apache Commons Lang (3.19.0) [Apache-2.0]
- Apache Commons Logging (1.3.6) [Apache-2.0]
- Apache Commons Pool (2.12.1) [Apache-2.0]
- Apache Log4j API (2.25.5) [Apache-2.0]
- Apache Log4j to SLF4J Adapter (2.25.5) [Apache-2.0]
- ClassMate (1.7.3) [Apache-2.0]
- Core functionality for the Reactor Netty library (1.3.6) [Apache-2.0]
- error-prone annotations (2.41.0) [Apache-2.0]
- Gson (2.13.2) [Apache-2.0]
- HdrHistogram (2.2.2) [BSD-2-Clause]
- Hibernate Validator Engine (9.0.1.Final) [Apache-2.0]
- HTTP functionality for the Reactor Netty library (1.3.6) [Apache-2.0]
- Jackson-annotations (2.22) [Apache-2.0]
- Jackson-core (2.21.6) [Apache-2.0]
- Jackson-core (3.2.2) [Apache-2.0]
- jackson-databind (2.21.6) [Apache-2.0]
- jackson-databind (3.2.2) [Apache-2.0]
- Jakarta Annotations API (3.0.0) [EPL-2.0]
- Jakarta Validation API (3.1.1) [Apache-2.0]
- JBoss Logging 3 (3.6.3.Final) [Apache-2.0]

--------------------------------------------------------------------------------
Package Title: Apache Commons Lang (3.19.0)

Package Locator: mvn+org.apache.commons:commons-lang3$3.19.0

Package Depth: Direct
--------------------------------------------------------------------------------


  Apache Commons Lang, a package of Java utility classes for the
  classes that are in java.lang's hierarchy, or are considered to be so
  standard as to justify existence in java.lang.

  The code is tested using the latest revision of the JDK for supported
  LTS releases: 8, 11, 17, 21 and 25 currently.
  See https://github.com/apache/commons-lang/blob/master/.github/workflows/maven.yml
  
  Please ensure your build environment is up-to-date and kindly report any build issues.
  

* Concluded Licenses *
Apache-2.0

* Package Info *

Authors: Adam Hooper, Adrian Ber, Al Chou, Alban Peignier, Alexander Day Chaffee, Allon Mureinik, Andrew C. Oliver, Antony Riley, Arturo Bernal, Arun Mammen Thomas, Ashwin Suresh, Benedikt Ritter, Benjamin Bentmann, Brian S O'Neill, C. Scott Ananian, Chas Honton, Chris Audley, Chris Feldhacker, Chris Hyzer, Chris Karcher, Chris Webb, Christopher Elkins, Craig R. McClanahan, Daniel Rall, Daniel Trebbien, Dave Meikle, David Leppik, David M. Sledge, Derek C. Ashmore, Dmitri Plotnikov, Duncan Jones, Ed Korthof, Eli Lindsey, Eric Pugh, Fabian Lange, Felipe Adorno, Fredrik Westermarck, Gary Gregory, Glen Stampoultzis, Greg Coladonato, Helge Tesgaard, Hendrik Maryns, Henning P. Schmiedehausen, Henri Yandell, Holger Hoffstatte, Holger Krauth, James Carman, James Sawle, Jan Sorensen, Janek Bogucki, Jason Gritman, Jeff Varszegi, Jin Xu, Joerg Schaible, Jon S. Stevens, Jonathan Baker, Justin Couch, Kasper Nielsen, Loic Guibert, Maarten Coene, Marc Johnson, Mario Winterer, Mark Dacek, Masato Tezuka, Matt Benson, Matthew Hawthorne, Matthias Eichel, Michael A. Smith, Michael Becke, Michael Davey, Michael Heuer, Michael Osipov, Michał Kordas, Mike Bowler, Mikhail Mazursky, Morgan Delagrange, Moritz Petersen, Nathan Beyer, Neeme Praks, Niall Pemberton, Nikolay Metchev, Nissim Karpenstein, Norm Deane, Ola Berg, Oliver Heger, Paul Benedict, Paul Jack, Pete Gieser, Peter Verhas, Rafal Krupinski, Rafal Krzewski, Ralph Schaer, Rand McNeely, Reuben Sivan, Ringo De Smet, Rob Tompkins, Robert Burrell Donkin, Robert Scholte, Roland Foerther, Russel Dittmar, Scott Sanders, Scott Stanchfield, Sean Brown, Sean C. Sullivan, Sean Schofield, Sebastien Riou, Shaun Kalley, Stefan Bodewig, Stepan Koltsov, Stephane Bailliez, Stephen Colebourne, Stephen Putman, Steve Downey, Steven Caswell, Sven Ludwig, Tetsuya Kaneuchi, Thiago Andrade, Tim O'Brien, Travis Reeder, Valentin Rocher, Ville Skytta
Package Manager: Maven
Project URL: https://commons.apache.org/proper/commons-lang/
Package Download URL: https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.19.0/commons-lang3-3.19.0-sources.jar
Dependency Path: org.apache.commons:commons-lang3
* Notice File(s) *
META-INF/NOTICE.txt
Apache Commons Lang
Copyright 2001-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).


--------------------------------------------------------------------------------
Package Title: Apache Commons Logging (1.3.6)

Package Locator: mvn+commons-logging:commons-logging$1.3.6

Package Depth: Transitive
--------------------------------------------------------------------------------

Apache Commons Logging is a thin adapter allowing configurable bridging to other,
    well-known logging systems.

* Concluded Licenses *
Apache-2.0

* Package Info *

Authors: Arturo Bernal, Berin Loritsch, Brian Stansberry, Costin Manolache, Craig McClanahan, Dennis Lundberg, Gary Gregory, Juozas Baliuka, Matthew P. Del Buono, Morgan Delagrange, Neeme Praks, Peter Donald, Peter Lawrey, Philippe Mouawad, Richard Sitze, Robert Burrell Donkin, Rodney Waldhoff, Scott Sanders, Simon Kitching, Thomas Neidhart, Vince Eagen
Package Manager: Maven
Project URL: https://commons.apache.org/proper/commons-logging/
Package Download URL: https://repo1.maven.org/maven2/commons-logging/commons-logging/1.3.6/commons-logging-1.3.6-sources.jar
Dependency Path: org.springframework.boot:spring-boot > org.springframework:spring-context > org.springframework:spring-core > commons-logging:commons-logging > org.springframework.boot:spring-boot > org.springframework:spring-core > commons-logging:commons-logging > org.springframework.boot:spring-boot-starter-oauth2-resource-server > org.springframework.boot:spring-boot-security-oauth2-resource-server > org.springframework.boot:spring-boot-security > org.springframework.security:spring-security-config > org.springframework.security:spring-security-core > org.springframework:spring-core > commons-logging:commons-logging > org.springframework.boot:spring-boot-starter-oauth2-resource-server > org.springframework.boot:spring-boot-security-oauth2-resource-server > org.springframework.boot:spring-boot-security > org.springframework.security:spring-security-config > org.springframework:spring-beans > org.springframework:spring-core > commons-logging:commons-logging > org.springframework.boot:spring-boot-starter-oauth2-resource-server > org.springframework.boot:spring-boot-security-oauth2-resource-server > org.springframework.boot:spring-boot-security > org.springframework.security:spring-security-config > org.springframework:spring-core > commons-logging:commons-logging > org.springframework.boot:spring-boot-starter-oauth2-resource-server > org.springframework.boot:spring-boot-security-oauth2-resource-server > org.springframework.boot:spring-boot-security > org.springframework.security:spring-security-web > org.springframework:spring-core > commons-logging:commons-logging > org.springframework.boot:spring-boot-starter-oauth2-resource-server > org.springframework.boot:spring-boot-security-oauth2-resource-server > org.springframework.security:spring-security-oauth2-jose > org.springframework.security:spring-security-oauth2-core > org.springframework:spring-core > commons-logging:commons-logging > org.springframework.boot:spring-boot-starter-oauth2-resource-server > org.springframework.boot:spring-boot-security-oauth2-resource-server > org.springframework.security:spring-security-oauth2-jose > org.springframework:spring-core > commons-logging:commons-logging > org.springframework.boot:spring-boot-starter-oauth2-resource-server > org.springframework.boot:spring-boot-security-oauth2-resource-server > org.springframework.security:spring-security-oauth2-resource-server > org.springframework:spring-core > commons-logging:commons-logging > org.springframework.boot:spring-boot-starter-security > org.springframework:spring-aop > org.springframework:spring-core > commons-logging:commons-logging
* Notice File(s) *
META-INF/NOTICE.txt
Apache Commons Logging
Copyright 2001-2026 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).


--------------------------------------------------------------------------------
Package Title: Apache Commons Pool (2.12.1)

Package Locator: mvn+org.apache.commons:commons-pool2$2.12.1

Package Depth: Transitive
--------------------------------------------------------------------------------

The Apache Commons Object Pooling Library.

* Concluded Licenses *
Apache-2.0

* Copyrights *
Apache-2.0
- Copyright (c)  inceptionYear}-{currentYear} {organizationName}. All rights reserved.&lt;/br&gt;

* Package Info *

Authors: Arturo Bernal, Craig McClanahan, David Weinrich, Dirk Verbeeck, Gary Gregory, Geir Magnusson, Matt Sicker, Morgan Delagrange, Phil Steitz, Robert Burrell Donkin, Rodney Waldhoff, Sandy McArthur, Simone Tripodi, Todd Carmichael
Package Manager: Maven
Project URL: https://commons.apache.org/proper/commons-pool/
Package Download URL: https://repo1.maven.org/maven2/org/apache/commons/commons-pool2/2.12.1/commons-pool2-2.12.1-sources.jar
Dependency Path: redis.clients:jedis > org.apache.commons:commons-pool2
* Notice File(s) *
META-INF/NOTICE.txt
Apache Commons Pool
Copyright 2001-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).


--------------------------------------------------------------------------------
Package Title: Apache Log4j API (2.25.5)

Package Locator: mvn+org.apache.logging.log4j:log4j-api$2.25.5

Package Depth: Transitive
--------------------------------------------------------------------------------

The Apache Log4j API

* Concluded Licenses *
Apache-2.0

* Package Info *

Authors: Bruce Brouwer, Carter Kozak, Christian Grobmeier, Gary Gregory, Matt Sicker, Mikael Ståldal, Nick Williams, Piotr P. Karwasz, Ralph Goers, Raman Gupta, Remko Popma, Ron Grabowski, Scott Deboy, Volkan Yazıcı
Package Manager: Maven
Project URL: https://logging.apache.org/log4j/3.x/
Package Download URL: https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.25.5/log4j-api-2.25.5-sources.jar
Dependency Path: org.springframework.boot:spring-boot-starter-actuator > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.apache.logging.log4j:log4j-api > org.springframework.boot:spring-boot-starter-actuator > org.springframework.boot:spring-boot-starter-micrometer-metrics > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.apache.logging.log4j:log4j-api > org.springframework.boot:spring-boot-starter-oauth2-resource-server > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.apache.logging.log4j:log4j-api > org.springframework.boot:spring-boot-starter-oauth2-resource-server > org.springframework.boot:spring-boot-starter-security > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.apache.logging.log4j:log4j-api > org.springframework.boot:spring-boot-starter-security > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.apache.logging.log4j:log4j-api > org.springframework.boot:spring-boot-starter-validation > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.apache.logging.log4j:log4j-api > org.springframework.boot:spring-boot-starter-web > org.springframework.boot:spring-boot-starter-jackson > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.apache.logging.log4j:log4j-api > org.springframework.boot:spring-boot-starter-web > org.springframework.boot:spring-boot-starter-tomcat > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.apache.logging.log4j:log4j-api > org.springframework.boot:spring-boot-starter-webflux > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.apache.logging.log4j:log4j-api > org.springframework.boot:spring-boot-starter-webflux > org.springframework.boot:spring-boot-starter-reactor-netty > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.apache.logging.log4j:log4j-api
* Notice File(s) *
META-INF/NOTICE
Apache Log4j API
Copyright 1999-2026 The Apache Software Foundation


This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).


--------------------------------------------------------------------------------
Package Title: Apache Log4j to SLF4J Adapter (2.25.5)

Package Locator: mvn+org.apache.logging.log4j:log4j-to-slf4j$2.25.5

Package Depth: Transitive
--------------------------------------------------------------------------------

The Apache Log4j binding between Log4j 2 API and SLF4J.

* Concluded Licenses *
Apache-2.0

* Package Info *

Authors: Bruce Brouwer, Carter Kozak, Christian Grobmeier, Gary Gregory, Matt Sicker, Mikael Ståldal, Nick Williams, Piotr P. Karwasz, Ralph Goers, Raman Gupta, Remko Popma, Ron Grabowski, Scott Deboy, Volkan Yazıcı
Package Manager: Maven
Project URL: https://logging.apache.org/log4j/3.x/
Package Download URL: https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-to-slf4j/2.25.5/log4j-to-slf4j-2.25.5-sources.jar
Dependency Path: org.springframework.boot:spring-boot-starter-actuator > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.springframework.boot:spring-boot-starter-actuator > org.springframework.boot:spring-boot-starter-micrometer-metrics > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.springframework.boot:spring-boot-starter-oauth2-resource-server > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.springframework.boot:spring-boot-starter-oauth2-resource-server > org.springframework.boot:spring-boot-starter-security > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.springframework.boot:spring-boot-starter-security > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.springframework.boot:spring-boot-starter-validation > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.springframework.boot:spring-boot-starter-web > org.springframework.boot:spring-boot-starter-jackson > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.springframework.boot:spring-boot-starter-web > org.springframework.boot:spring-boot-starter-tomcat > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.springframework.boot:spring-boot-starter-webflux > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j > org.springframework.boot:spring-boot-starter-webflux > org.springframework.boot:spring-boot-starter-reactor-netty > org.springframework.boot:spring-boot-starter > org.springframework.boot:spring-boot-starter-logging > org.apache.logging.log4j:log4j-to-slf4j
* Notice File(s) *
META-INF/NOTICE
Log4j API to SLF4J Adapter
Copyright 1999-2026 The Apache Software Foundation


This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).


--------------------------------------------------------------------------------
Package Title: ClassMate (1.7.3)

Package Locator: mvn+com.fasterxml:classmate$1.7.3

Package Depth: Transitive
--------------------------------------------------------------------------------

Library for introspecting types with full generic information
        including resolving of field and method types.
    

* Concluded Licenses *
Apache-2.0

* Package Info *

Authors: Brian Langel, Tatu Saloranta
Package Manager: Maven
Project URL: https://github.com/FasterXML/java-classmate
Package Download URL: https://repo1.maven.org/maven2/com/fasterxml/classmate/1.7.3/classmate-1.7.3-sources.jar
Dependency Path: org.springframework.boot:spring-boot-starter-validation > org.springframework.boot:spring-boot-validation > org.hibernate.validator:hibernate-validator > com.fasterxml:classmate
* Notice File(s) *
META-INF/NOTICE
Java ClassMate library was originally written by Tatu Saloranta (tatu.saloranta@iki.fi)

Other developers who have contributed code are:

* Brian Langel

## Copyright

Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)

--------------------------------------------------------------------------------
Package Title: Core functionality for the Reactor Netty library (1.3.6)

Package Locator: mvn+io.projectreactor.netty:reactor-netty-core$1.3.6

Package Depth: Transitive
--------------------------------------------------------------------------------

Core functionality for the Reactor Netty library

* Concluded Licenses *
Apache-2.0

* Copyrights *
Apache-2.0
- Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2018-2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2021-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2011-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2019-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2022-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2020-2021 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2011-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2019-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2017-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2017-2021 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2017-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2018-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2019-2024 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2018-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2020-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2017-2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2022-2024 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2018-2021 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2020-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2019-2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2022-2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2020-2022 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.

* Package Info *

Authors: Simon Baslé, Violeta Georgieva
Package Manager: Maven
Project URL: https://github.com/reactor/reactor-netty
Package Download URL: https://repo1.maven.org/maven2/io/projectreactor/netty/reactor-netty-core/1.3.6/reactor-netty-core-1.3.6-sources.jar
Dependency Path: org.springframework.boot:spring-boot-starter-webflux > org.springframework.boot:spring-boot-starter-reactor-netty > org.springframework.boot:spring-boot-reactor-netty > io.projectreactor.netty:reactor-netty-http > io.projectreactor.netty:reactor-netty-core

--------------------------------------------------------------------------------
Package Title: error-prone annotations (2.41.0)

Package Locator: mvn+com.google.errorprone:error_prone_annotations$2.41.0

Package Depth: Transitive
--------------------------------------------------------------------------------

Error Prone is a static analysis tool for Java that catches common programming mistakes at compile-time.

* Concluded Licenses *
Apache-2.0

* Copyrights *
Apache-2.0
- Copyright (c) 2015 The Error Prone Authors.
- Copyright (c) 2016 The Error Prone Authors.
- Copyright (c) 2021 The Error Prone Authors.
- Copyright (c) 2023 The Error Prone Authors.
- Copyright (c) 2014 The Error Prone Authors.
- Copyright (c) 2017 The Error Prone Authors.
- Copyright (c) 2018 The Error Prone Authors.

* Package Info *

Authors: Eddie Aftandilian
Package Manager: Maven
Project URL: https://errorprone.info/
Package Download URL: https://repo1.maven.org/maven2/com/google/errorprone/error_prone_annotations/2.41.0/error_prone_annotations-2.41.0-sources.jar
Dependency Path: redis.clients:jedis > com.google.code.gson:gson > com.google.errorprone:error_prone_annotations

--------------------------------------------------------------------------------
Package Title: Gson (2.13.2)

Package Locator: mvn+com.google.code.gson:gson$2.13.2

Package Depth: Transitive
--------------------------------------------------------------------------------

Gson JSON library

* Concluded Licenses *
Apache-2.0

* Copyrights *
Apache-2.0
- Copyright (c) 2008 Google LLC
- Copyright (c) 2008 Google Inc.
- Copyright (c) 2009 Google Inc.
- Copyright (c) 2011 Google Inc.
- Copyright (c) 2010 Google Inc.
- Copyright (c) 2022 Google Inc.
- Copyright (c) 2015 Google Inc.
- Copyright (c) 2020 Google Inc.
- Copyright (c) 2018 Google Inc.
- Copyright (c) 2021 Google Inc.
- Copyright (c) 2014 Google Inc.
- Copyright (c) 2010 The Android Open Source Project
- Copyright (c) 2012 Google Inc.
- Copyright (c) 2024 Google Inc.
- Copyright (c) 2017 The Gson authors
- Copyright (c) 2018 The Gson authors

* Package Info *

Authors: Inderjeet Singh, Joel Leitch
Package Manager: Maven
Project URL: https://github.com/google/gson
Package Download URL: https://repo1.maven.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2-sources.jar
Dependency Path: redis.clients:jedis > com.google.code.gson:gson

--------------------------------------------------------------------------------
Package Title: HdrHistogram (2.2.2)

Package Locator: mvn+org.hdrhistogram:HdrHistogram$2.2.2

Package Depth: Transitive
--------------------------------------------------------------------------------


        HdrHistogram supports the recording and analyzing sampled data value
        counts across a configurable integer value range with configurable value
        precision within the range. Value precision is expressed as the number of
        significant digits in the value recording, and provides control over value
        quantization behavior across the value range and the subsequent value
        resolution at any given level.
    

* Concluded Licenses *
BSD-2-Clause

* Copyrights *
BSD-2-Clause
- Copyright (c) 2012, 2013, 2014, 2015, 2016 Gil Tene
- Copyright (c) 2014 Michael Barker
- Copyright (c) 2014 Matt Warren

* Package Info *

Authors: Gil Tene
Package Manager: Maven
Project URL: http://hdrhistogram.github.io/HdrHistogram/
Package Download URL: https://repo1.maven.org/maven2/org/hdrhistogram/HdrHistogram/2.2.2/HdrHistogram-2.2.2-sources.jar
Dependency Path: org.springframework.boot:spring-boot-starter-actuator > io.micrometer:micrometer-jakarta9 > io.micrometer:micrometer-core > org.hdrhistogram:HdrHistogram > org.springframework.boot:spring-boot-starter-actuator > org.springframework.boot:spring-boot-starter-micrometer-metrics > org.springframework.boot:spring-boot-micrometer-metrics > io.micrometer:micrometer-core > org.hdrhistogram:HdrHistogram

--------------------------------------------------------------------------------
Package Title: Hibernate Validator Engine (9.0.1.Final)

Package Locator: mvn+org.hibernate.validator:hibernate-validator$9.0.1.Final

Package Depth: Transitive
--------------------------------------------------------------------------------

Hibernate's Jakarta Validation reference implementation.

* Concluded Licenses *
Apache-2.0

* Copyrights *
Apache-2.0
- Copyright (c)  Red Hat Inc. and Hibernate Authors

* Package Info *

Authors: Davide D'Alto, Emmanuel Bernard, Guillaume Smet, Gunnar Morling, Hardy Ferentschik, Kevin Pollet, Marko Bekhta
Package Manager: Maven
Project URL: https://hibernate.org/validator
Package Download URL: https://repo1.maven.org/maven2/org/hibernate/validator/hibernate-validator/9.0.1.Final/hibernate-validator-9.0.1.Final-sources.jar
Dependency Path: org.springframework.boot:spring-boot-starter-validation > org.springframework.boot:spring-boot-validation > org.hibernate.validator:hibernate-validator

--------------------------------------------------------------------------------
Package Title: HTTP functionality for the Reactor Netty library (1.3.6)

Package Locator: mvn+io.projectreactor.netty:reactor-netty-http$1.3.6

Package Depth: Transitive
--------------------------------------------------------------------------------

HTTP functionality for the Reactor Netty library

* Concluded Licenses *
Apache-2.0

* Copyrights *
Apache-2.0
- Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2020-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2019-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2022-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2025-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2024-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2017-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2011-2024 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2019-2024 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2021-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2021-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2017-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2018-2024 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2018-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2024-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2011-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2019-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2018-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2019-2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2022-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2020-2025 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2022-2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2011-2026 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2022-2024 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2018-2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2018-2021 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2023-2024 VMware, Inc. or its affiliates, All Rights Reserved.
- Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.

* Package Info *

Authors: Simon Baslé, Violeta Georgieva
Package Manager: Maven
Project URL: https://github.com/reactor/reactor-netty
Package Download URL: https://repo1.maven.org/maven2/io/projectreactor/netty/reactor-netty-http/1.3.6/reactor-netty-http-1.3.6-sources.jar
Dependency Path: org.springframework.boot:spring-boot-starter-webflux > org.springframework.boot:spring-boot-starter-reactor-netty > org.springframework.boot:spring-boot-reactor-netty > io.projectreactor.netty:reactor-netty-http

--------------------------------------------------------------------------------
Package Title: Jackson-annotations (2.22)

Package Locator: mvn+com.fasterxml.jackson.core:jackson-annotations$2.22

Package Depth: Transitive
--------------------------------------------------------------------------------

Core annotations used for value types, used by Jackson data binding package.
  

* Concluded Licenses *
Apache-2.0

* Package Info *

Authors: Tatu Saloranta
Package Manager: Maven
Project URL: http://github.com/FasterXML/jackson
Package Download URL: https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22-sources.jar
Dependency Path: com.fasterxml.jackson.core:jackson-databind > com.fasterxml.jackson.core:jackson-annotations > org.springframework.boot:spring-boot-starter-web > org.springframework.boot:spring-boot-starter-jackson > org.springframework.boot:spring-boot-jackson > tools.jackson.core:jackson-databind > com.fasterxml.jackson.core:jackson-annotations > org.springframework.boot:spring-boot-starter-webflux > org.springframework.boot:spring-boot-starter-jackson > org.springframework.boot:spring-boot-jackson > tools.jackson.core:jackson-databind > com.fasterxml.jackson.core:jackson-annotations
* Notice File(s) *
META-INF/NOTICE
# Jackson JSON processor

Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.

## Copyright

Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)

## Licensing

Jackson 2.x core and extension components are licensed under Apache License 2.0
To find the details that apply to this artifact see the accompanying LICENSE file.

## Credits

A list of contributors may be found from CREDITS(-2.x) file, which is included
in some artifacts (usually source distributions); but is always available
from the source code management (SCM) system project uses.


--------------------------------------------------------------------------------
Package Title: Jackson-core (2.21.6)

Package Locator: mvn+com.fasterxml.jackson.core:jackson-core$2.21.6

Package Depth: Transitive
--------------------------------------------------------------------------------

Core Jackson processing abstractions (aka Streaming API), implementation for JSON

* Concluded Licenses *
Apache-2.0

* Package Info *

Authors: Tatu Saloranta
Package Manager: Maven
Project URL: https://github.com/FasterXML/jackson-core
Package Download URL: https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.21.6/jackson-core-2.21.6-sources.jar
Dependency Path: com.fasterxml.jackson.core:jackson-databind > com.fasterxml.jackson.core:jackson-core

--------------------------------------------------------------------------------
Package Title: Jackson-core (3.2.2)

Package Locator: mvn+tools.jackson.core:jackson-core$3.2.2

Package Depth: Transitive
--------------------------------------------------------------------------------

Core Jackson processing abstractions (aka Streaming API), implementation for JSON

* Concluded Licenses *
Apache-2.0

* Package Info *

Authors: Tatu Saloranta
Package Manager: Maven
Project URL: https://github.com/FasterXML/jackson-core
Package Download URL: https://repo1.maven.org/maven2/tools/jackson/core/jackson-core/3.2.2/jackson-core-3.2.2-sources.jar
Dependency Path: org.springframework.boot:spring-boot-starter-web > org.springframework.boot:spring-boot-starter-jackson > org.springframework.boot:spring-boot-jackson > tools.jackson.core:jackson-databind > tools.jackson.core:jackson-core > org.springframework.boot:spring-boot-starter-webflux > org.springframework.boot:spring-boot-starter-jackson > org.springframework.boot:spring-boot-jackson > tools.jackson.core:jackson-databind > tools.jackson.core:jackson-core

--------------------------------------------------------------------------------
Package Title: jackson-databind (2.21.6)

Package Locator: mvn+com.fasterxml.jackson.core:jackson-databind$2.21.6

Package Depth: Direct
--------------------------------------------------------------------------------

General data-binding functionality for Jackson: works on core streaming API

* Concluded Licenses *
Apache-2.0

* Copyrights *
Apache-2.0
- Copyright (c) 2011 Google Inc. All Rights Reserved.
- Copyright (c) 2010 Google Inc. All Rights Reserved.

* Package Info *

Authors: Tatu Saloranta
Package Manager: Maven
Project URL: https://github.com/FasterXML/jackson
Package Download URL: https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.21.6/jackson-databind-2.21.6-sources.jar
Dependency Path: com.fasterxml.jackson.core:jackson-databind
* Notice File(s) *
META-INF/NOTICE
# Jackson JSON processor

Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.

## Copyright

Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)

## Licensing

Jackson 2.x core and extension components are licensed under Apache License 2.0
To find the details that apply to this artifact see the accompanying LICENSE file.

## Credits

A list of contributors may be found from CREDITS(-2.x) file, which is included
in some artifacts (usually source distributions); but is always available
from the source code management (SCM) system project uses.


--------------------------------------------------------------------------------
Package Title: jackson-databind (3.2.2)

Package Locator: mvn+tools.jackson.core:jackson-databind$3.2.2

Package Depth: Transitive
--------------------------------------------------------------------------------

General data-binding functionality for Jackson: works on core streaming API

* Concluded Licenses *
Apache-2.0

* Copyrights *
Apache-2.0
- Copyright (c) 2013 FasterXML.com
- Copyright (c) 2010 Google Inc. All Rights Reserved.
- Copyright (c) 2011 Google Inc. All Rights Reserved.

* Package Info *

Authors: Tatu Saloranta
Package Manager: Maven
Project URL: https://github.com/FasterXML/jackson
Package Download URL: https://repo1.maven.org/maven2/tools/jackson/core/jackson-databind/3.2.2/jackson-databind-3.2.2-sources.jar
Dependency Path: org.springframework.boot:spring-boot-starter-web > org.springframework.boot:spring-boot-starter-jackson > org.springframework.boot:spring-boot-jackson > tools.jackson.core:jackson-databind > org.springframework.boot:spring-boot-starter-webflux > org.springframework.boot:spring-boot-starter-jackson > org.springframework.boot:spring-boot-jackson > tools.jackson.core:jackson-databind
* Notice File(s) *
META-INF/NOTICE
# Jackson JSON processor

Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.

## Copyright

Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)

## Licensing

Jackson 3.x core and extension components are licensed under Apache License 2.0
To find the details that apply to this artifact see the accompanying LICENSE file.

## Credits

A list of contributors may be found from CREDITS file, which is included
in some artifacts (usually source distributions); but is always available
from the source code management (SCM) system project uses.


--------------------------------------------------------------------------------
Package Title: Jakarta Annotations API (3.0.0)

Package Locator: mvn+jakarta.annotation:jakarta.annotation-api$3.0.0

Package Depth: Transitive
--------------------------------------------------------------------------------

Jakarta Annotations API

* Concluded Licenses *
EPL-2.0

* Copyrights *
EPL-2.0
- Copyright (c) 1989, 1991 Free Software Foundation, Inc.
- Copyright (c) 2005, 2020 Oracle and/or its affiliates. All rights reserved.
- Copyright (c) 2009, 2023 Oracle and/or its affiliates. All rights reserved.
- Copyright (c) 2012, 2024 Oracle and/or its affiliates. All rights reserved.
- Copyright (c)  ignoreyear>false</copyright.ignoreyear>
- Copyright (c)  ignoreyear>
- Copyright (c)  scmonly>true</copyright.scmonly>
- Copyright (c)  scmonly>
- Copyright (c)  update>false</copyright.update>
- Copyright (c)  update>
- Copyright (c)  groupId>
- Copyright (c)  maven-plugin</artifactId>
- Copyright (c)  scmonly}</scmOnly>
- Copyright (c)  update}</update>
- Copyright (c)  ignoreyear}</ignoreYear>
- Copyright (c) 2019, 2024 Eclipse Foundation. All rights reserved.<br>
- Copyright (c) 2005, 2023 Oracle and/or its affiliates. All rights reserved.
- Copyright (c) 2018, 2020 Oracle and/or its affiliates. All rights reserved.
- Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
- Copyright (c) 2021 Oracle and/or its affiliates. All rights reserved.
- Copyright (c)  the software, and

* Package Info *

Authors: Dmitry Kornilov, Eclipse EE4J Developers, Linda De Michiel
Package Manager: Maven
Project URL: https://projects.eclipse.org/projects/ee4j.ca
Package Download URL: https://repo1.maven.org/maven2/jakarta/annotation/jakarta.annotation-api/3.0.0/jakarta.annotation-api-3.0.0-sources.jar
Dependency Path: org.springframework.boot:spring-boot-starter-actuator > org.springframework.boot:spring-boot-starter > jakarta.annotation:jakarta.annotation-api > org.springframework.boot:spring-boot-starter-actuator > org.springframework.boot:spring-boot-starter-micrometer-metrics > org.springframework.boot:spring-boot-starter > jakarta.annotation:jakarta.annotation-api > org.springframework.boot:spring-boot-starter-oauth2-resource-server > org.springframework.boot:spring-boot-starter > jakarta.annotation:jakarta.annotation-api > org.springframework.boot:spring-boot-starter-security > org.springframework.boot:spring-boot-starter > jakarta.annotation:jakarta.annotation-api > org.springframework.boot:spring-boot-starter-web > org.springframework.boot:spring-boot-starter-jackson > org.springframework.boot:spring-boot-starter > jakarta.annotation:jakarta.annotation-api > org.springframework.boot:spring-boot-starter-web > org.springframework.boot:spring-boot-starter-tomcat > org.springframework.boot:spring-boot-starter > jakarta.annotation:jakarta.annotation-api > org.springframework.boot:spring-boot-starter-web > org.springframework.boot:spring-boot-starter-tomcat > org.springframework.boot:spring-boot-starter-tomcat-runtime > jakarta.annotation:jakarta.annotation-api > org.springframework.boot:spring-boot-starter-web > org.springframework.boot:spring-boot-starter-tomcat > org.springframework.boot:spring-boot-starter-tomcat-runtime > org.springframework.boot:spring-boot-tomcat > jakarta.annotation:jakarta.annotation-api > org.springframework.boot:spring-boot-starter-web > org.springframework.boot:spring-boot-starter-tomcat > org.springframework.boot:spring-boot-tomcat > jakarta.annotation:jakarta.annotation-api > org.springframework.boot:spring-boot-starter-webflux > org.springframework.boot:spring-boot-starter-reactor-netty > org.springframework.boot:spring-boot-starter > jakarta.annotation:jakarta.annotation-api

--------------------------------------------------------------------------------
Package Title: Jakarta Validation API (3.1.1)

Package Locator: mvn+jakarta.validation:jakarta.validation-api$3.1.1

Package Depth: Transitive
--------------------------------------------------------------------------------

Jakarta Validation API

* Concluded Licenses *
Apache-2.0

* Copyrights *
Apache-2.0
- Copyright (c) 2019,2023 Eclipse Foundation.<br>

* Package Info *

Authors: Emmanuel Bernard, Guillaume Smet, Gunnar Morling, Hardy Ferentschik
Package Manager: Maven
Project URL: https://beanvalidation.org/
Package Download URL: https://repo1.maven.org/maven2/jakarta/validation/jakarta.validation-api/3.1.1/jakarta.validation-api-3.1.1-sources.jar
Dependency Path: org.springframework.boot:spring-boot-starter-validation > org.springframework.boot:spring-boot-validation > org.hibernate.validator:hibernate-validator > jakarta.validation:jakarta.validation-api

--------------------------------------------------------------------------------
Package Title: JBoss Logging 3 (3.6.3.Final)

Package Locator: mvn+org.jboss.logging:jboss-logging$3.6.3.Final

Package Depth: Transitive
--------------------------------------------------------------------------------

The JBoss Logging Framework

* Concluded Licenses *
Apache-2.0

* Copyrights *
Apache-2.0
- Copyright (c) 2023 Red Hat, Inc.

* Package Info *

Authors: JBoss.org Community
Package Manager: Maven
Project URL: https://www.jboss.org/
Package Download URL: https://repo1.maven.org/maven2/org/jboss/logging/jboss-logging/3.6.3.Final/jboss-logging-3.6.3.Final-sources.jar
Dependency Path: org.springframework.boot:spring-boot-starter-validation > org.springframework.boot:spring-boot-validation > org.hibernate.validator:hibernate-validator > org.jboss.logging:jboss-logging


--------------------------------------------------------------------------------
--------------------------------------------------------------------------------

Report Generated by FOSSA on 2026-9-23