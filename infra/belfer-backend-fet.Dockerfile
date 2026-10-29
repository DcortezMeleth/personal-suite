# The belFER backend, with FET inside it.
#
# FET is a separate program rather than a library, and the backend has to be
# able to run it. Of the ways to arrange that, this is the dull one: no Docker
# socket mounted into the service (which is root on the host by another name),
# no sidecar with a protocol of its own to invent and debug, no shared volume
# whose path has to mean the same thing on both sides of a mount. The command
# is `fet-cl`, the way it would be on a machine with the package installed,
# and belfer.fet.command says exactly that.
#
# Layered rather than built: the image underneath comes from Bazel, which has
# no RUN step, and the layer needs a package manager. So the order is
#
#   bazel run //services/belFER/backend:server_image_load
#   docker compose build belfer-backend
#
# and compose knows the second depends on the first having happened.
#
# The base is eclipse-temurin:25-jre-noble, so this is Ubuntu's FET — 6.8.5
# rather than the 7.0.8 Debian ships. That difference is recorded rather than
# papered over: see services/belFER/fet/Dockerfile, which is built from the
# same archive for the same reason, so that a laptop and the home lab run the
# same engine. FET warns that the file declares a newer format and carries on;
# it lays this school out completely, and ADR 0004's whole arrangement is that
# we check what comes back against our own rules rather than trusting it.
FROM belfer-backend:local

USER root
RUN apt-get update \
 && apt-get install --no-install-recommends -y fet \
 && rm -rf /var/lib/apt/lists/*

# Somewhere writable for the file FET is given and the directory it answers
# in. Not the default under $HOME: a container's home is an accident of the
# base image, and this should not change if that does.
ENV BELFER_FET_WORK_ROOT=/var/tmp/belfer-fet
ENV BELFER_FET_COMMAND="fet-cl --inputfile={dir}/school.fet --outputdir={dir}/out"
