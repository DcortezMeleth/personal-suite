# FET's command-line timetabler, in a container.
#
# FET generates; we check what it produced against our own rules (ADR 0004).
# The macOS build ships only the graphical program, and building the
# command-line one needs Qt, so this is how a laptop gets the same thing the
# home lab will run — and the image is that same thing, not an approximation
# of it.
#
# Ubuntu rather than Debian, and that is the only reason: it is the archive
# the backend image's base draws from, so the FET in this image and the FET
# compose puts inside the backend are the same build of the same version. A
# laptop and the home lab running different engines is the kind of difference
# that produces a bug nobody can reproduce — and this is the component whose
# output every measurement in SOLVER_EVALUATION.md is about.
#
# The cost is version 6.8.5 instead of the 7.0.8 Debian ships. It lays this
# school out completely; it warns that our file declares a newer format and
# continues. ADR 0004 is what makes that tolerable: nothing here is trusted,
# every week it returns is checked against our own rules before anyone sees it.
#
# Ubuntu packages FET for arm64 as well as x86-64, which is what makes this
# work without emulation on an Apple laptop.
#
# FET is AGPL v3. This image is built locally and not published; publishing it
# would mean offering FET's source alongside, which is a link rather than a
# difficulty, but it is a thing to do knowingly.
FROM ubuntu:24.04

RUN apt-get update \
 && apt-get install --no-install-recommends -y fet \
 && rm -rf /var/lib/apt/lists/*

# A timetable is one file in and one directory out, so the container needs
# nothing of its own: mount a directory and name files inside it.
WORKDIR /data
ENTRYPOINT ["fet-cl"]
CMD ["--help"]
