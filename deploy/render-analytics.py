#!/usr/bin/env python3
"""Render the statistics ingress with an explicit, validated IP allowlist."""
import ipaddress
import json
import os
from pathlib import Path
import sys


def allowed_networks(raw):
    if not raw.strip():
        raise ValueError("ANALYTICS_ALLOWED_IPS 未设置；不会默认开放公网访问")
    result = []
    for item in raw.split(","):
        network = ipaddress.ip_network(item.strip(), strict=True)
        if network.prefixlen == 0:
            raise ValueError("不允许使用 /0 放行所有地址")
        if str(network) not in result:
            result.append(str(network))
    return result


def main():
    try:
        networks = allowed_networks(os.environ.get("ANALYTICS_ALLOWED_IPS", ""))
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 1
    template = Path(__file__).with_name("k8s").joinpath("lumora-analytics.yaml").read_text()
    print(template.replace("__ANALYTICS_HOST__", sys.argv[1])
          .replace("__MANIFEST_HASH__", sys.argv[2])
          .replace("__ANALYTICS_ALLOWED_IPS__", json.dumps(networks)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
