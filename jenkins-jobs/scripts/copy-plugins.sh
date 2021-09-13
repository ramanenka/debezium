#! /usr/bin/env bash
set -x
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
DOCKER_FILE=${DIR}/../docker/artifact-server/Dockerfile
PLUGIN_DIR="plugins"
EXTRA_LIBS=""

OPTS=$(getopt -o d:a:l:f:r:o:t: --long dir:,archive-urls:,libs:,dockerfile:,registry:,organisation:,tag:,dest-login:,dest-pass:,img-output: -n 'parse-options' -- "$@")
if [ $? != 0 ] ; then echo "Failed parsing options." >&2 ; exit 1 ; fi
eval set -- "$OPTS"

while true; do
  case "$1" in
    -d | --dir )                BUILD_DIR=$2;
                                PLUGIN_DIR=${BUILD_DIR}/plugins;    shift; shift ;;
    -a | --archive-urls )       ARCHIVE_URLS=$2;                    shift; shift ;;
    -l | --libs )               EXTRA_LIBS=$2;                      shift; shift ;;
    -f | --dockerfile )         DOCKER_FILE=$2;                     shift; shift ;;
    -r | --registry )           REGISTRY=$2;                        shift; shift ;;
    -o | --organisation )       ORGANISATION=$2;                    shift; shift ;;
    -t | --tag )                TAG=$2;                             shift; shift ;;
    --dest-login )              DEST_LOGIN=$2;                      shift; shift ;;
    --dest-pass )               DEST_PASS=$2;                       shift; shift ;;
    --img-output )              IMAGE_OUTPUT_FILE=$2;               shift; shift ;;
    -h | --help )               PRINT_HELP=true;                    shift ;;
    -- ) shift; break ;;
    * ) break ;;
  esac
done

if [ ! -z "${DEST_LOGIN}" ] ; then
  docker login -u "${DEST_LOGIN}" -p "${DEST_PASS}" "${REGISTRY}"
fi

echo "Creating plugin directory ${PLUGIN_DIR}"
mkdir -p "${PLUGIN_DIR}"

pushd "${PLUGIN_DIR}" || exit
for archive in ${ARCHIVE_URLS}; do
    echo "[Processing] ${archive}"
    lib=$(echo ${archive} | awk -F "::"  '{print $1}' | xargs)
    dest=$(echo ${archive} |  awk -F "::"  '{print $2}' | xargs)

    if [ -z "$dest" ] ; then
      curl -OJs "${lib}"
    else
      mkdir -p "${dest}" && pushd ${dest} && curl -OJs "${lib}" && popd || exit
    fi
    connectors_version=$(echo "$archive" | sed -rn 's|.*AMQ-CDC-(.*)/.*$|\1|p')
done

for input in ${EXTRA_LIBS}; do
    echo "[Processing] ${input} "
    lib=$(echo ${input} | awk -F "::"  '{print $1}' | xargs)
    dest=$(echo ${input} |  awk -F "::"  '{print $2}' | xargs)

    if [ -z "$dest" ] ; then
      curl -OJs "${lib}"
    else
      mkdir -p "${dest}" && pushd ${dest} && curl -OJs "${lib}" && popd || exit
    fi

done
popd || exit

echo "Copying scripts to" "${BUILD_DIR}"
cp "${DIR}"/../docker/artifact-server/* "$BUILD_DIR"

echo "Copying Dockerfile to" "${BUILD_DIR}"
cp "$DOCKER_FILE" "$BUILD_DIR"

if [ -z "$TAG" ] ; then
  image_dbz=dbz-artifact-server:dbz-${connectors_version}
  target=${REGISTRY}/${ORGANISATION}/${image_dbz}
else
  target=$TAG
fi

pushd "${BUILD_DIR}" || exit
echo "[Build] Building ${image_dbz}"
docker build . -t "$target"
popd || exit

echo "[Build] Pushing image ${target}"
docker push ${target}
[[ -z "${IMAGE_OUTPUT_FILE}" ]] || echo $target >> ${IMAGE_OUTPUT_FILE}
