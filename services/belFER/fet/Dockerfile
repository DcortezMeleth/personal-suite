# FET's command-line timetabler, in a container.
#
# FET generates; we check what it produced against our own rules (ADR 0004).
# The macOS build ships only the graphical program, so this is how a laptop
# gets the command-line one — and it is the same build the home lab runs
# (infra/belfer-backend-fet.Dockerfile builds it from the same source with the
# same recipe), because a laptop and the home lab running different engines is
# the kind of difference that produces a bug nobody can reproduce.
#
# Built from upstream source, pinned by version and checksum, rather than taken
# from a distribution. Ubuntu Noble packages 6.8.5, which is about forty
# releases behind, and that cost more than a warning: 6.8.5 cannot say "an
# activity begins or ends the students' day", which is this school's
# religia/etyka rule as the school states it (RULES.md S2), and 7.5.4 fixed
# "logic bugs in the generation algorithm". Upstream publishes a GNU/Linux
# binary for x86-64 only, so an Apple laptop has to compile it either way.
#
# Only fet-cl is built. It needs QtCore and nothing graphical, which keeps the
# image small; QMake with the Qt 6 Ubuntu ships is a supported way to build it
# (FET's README: QMake works with Qt 5.15 or later; CMake would need Qt 6.10).
#
# To move to a newer FET: change both ARGs here and in the backend Dockerfile,
# rebuild, and record it in docs/RUNLOG.md — the engine is part of every
# result. The previous engine stays available as belfer-fet:<version> if it is
# tagged before rebuilding, which is how two engines are compared on one file.
#
# FET is AGPL v3. This image is built locally and not published; publishing it
# would mean offering FET's source alongside, which is a link rather than a
# difficulty, but it is a thing to do knowingly.
FROM ubuntu:24.04 AS build

ARG FET_VERSION=7.12.1
ARG FET_SHA256=6a4305d5dda5efecde920fafc7c6b51d80ce47966b9e8e60bb76078da0bd4246

RUN apt-get update \
 && apt-get install --no-install-recommends -y \
      build-essential ca-certificates curl xz-utils qt6-base-dev qmake6 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /src
RUN curl -fsSL -o fet.tar.xz "https://lalescu.ro/liviu/fet/download/fet-${FET_VERSION}.tar.xz" \
 && echo "${FET_SHA256}  fet.tar.xz" | sha256sum -c - \
 && tar -xJf fet.tar.xz

WORKDIR /src/fet-${FET_VERSION}/src
RUN qmake6 src-cl.pro \
 && make -j"$(nproc)" \
 && strip ../fet-cl \
 && cp ../fet-cl /fet-cl

FROM ubuntu:24.04

RUN apt-get update \
 && apt-get install --no-install-recommends -y libqt6core6t64 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /fet-cl /usr/bin/fet-cl

# A timetable is one file in and one directory out, so the container needs
# nothing of its own: mount a directory and name files inside it.
WORKDIR /data
ENTRYPOINT ["fet-cl"]
CMD ["--help"]
