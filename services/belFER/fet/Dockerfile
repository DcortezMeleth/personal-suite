# FET's command-line timetabler, in a container.
#
# FET generates; we check what it produced against our own rules (ADR 0004).
# The macOS build ships only the graphical program, and building the
# command-line one needs Qt, so this is how a laptop gets the same thing the
# home lab will run — and the image is that same thing, not an approximation
# of it.
#
# Debian packages FET for arm64 as well as x86-64, which is what makes this
# work without emulation on an Apple laptop.
#
# FET is AGPL v3. This image is built locally and not published; publishing it
# would mean offering FET's source alongside, which is a link rather than a
# difficulty, but it is a thing to do knowingly.
FROM debian:stable-slim

RUN apt-get update \
 && apt-get install --no-install-recommends -y fet \
 && rm -rf /var/lib/apt/lists/*

# A timetable is one file in and one directory out, so the container needs
# nothing of its own: mount a directory and name files inside it.
WORKDIR /data
ENTRYPOINT ["fet-cl"]
CMD ["--help"]
