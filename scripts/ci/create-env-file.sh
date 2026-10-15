#!/usr/bin/env bash

set -euo pipefail

APPLICATION="${1:?application name for the ingestion key required}"

cp .env.example .env
sed -i "s/^SPRING_DATASOURCE_PASSWORD=.*/SPRING_DATASOURCE_PASSWORD=$(openssl rand -hex 24)/" .env
sed -i "s/^TRACELITE_SECURITY_MANAGEMENTKEY=.*/TRACELITE_SECURITY_MANAGEMENTKEY=$(openssl rand -hex 32)/" .env
{
  echo "TRACELITE_SECURITY_PROJECTS_0_APPLICATION=${APPLICATION}"
  echo "TRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY=$(openssl rand -hex 32)"
} >> .env
