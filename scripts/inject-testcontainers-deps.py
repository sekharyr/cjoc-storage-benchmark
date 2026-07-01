#!/usr/bin/env python3
"""Adds Testcontainers test dependencies to a checked-out workload's pom.xml
so resources/BenchPostgresIT.java has something to compile/run against.
spring-petclinic-rest ships no Testcontainers dependency itself, so the
Checkout stage (vars/benchStages.groovy) runs this against workload/pom.xml
before the build matrix starts. No explicit <version> tags — relies on
spring-boot-starter-parent's own dependency management for compatible
Testcontainers versions.

Uses ElementTree instead of a text/sed patch because a pom can have two
<dependencies> elements (root and the one nested in <dependencyManagement>);
only the root one should get new entries.

Usage: inject-testcontainers-deps.py <path-to-pom.xml>
"""
import sys
import xml.etree.ElementTree as ET

POM_NS = 'http://maven.apache.org/POM/4.0.0'
NEW_DEPENDENCIES = [
    ('org.springframework.boot', 'spring-boot-testcontainers'),
    # Testcontainers 2.x renamed its module artifacts with a testcontainers-
    # prefix (testcontainers-junit-jupiter, testcontainers-postgresql) instead
    # of the old bare names (junit-jupiter, postgresql) — verified against the
    # actual testcontainers-bom Spring Boot 4.x's dependency management
    # imports; the old names aren't in that BOM and fail with a
    # "version is missing" error at build time.
    ('org.testcontainers', 'testcontainers-junit-jupiter'),
    ('org.testcontainers', 'testcontainers-postgresql'),
]


def qualified(tag):
    return f'{{{POM_NS}}}{tag}'


def main():
    pom_path = sys.argv[1] if len(sys.argv) > 1 else 'pom.xml'
    ET.register_namespace('', POM_NS)
    tree = ET.parse(pom_path)
    root = tree.getroot()

    dependencies = root.find(qualified('dependencies'))
    if dependencies is None:
        dependencies = ET.SubElement(root, qualified('dependencies'))

    for group_id, artifact_id in NEW_DEPENDENCIES:
        dependency = ET.SubElement(dependencies, qualified('dependency'))
        ET.SubElement(dependency, qualified('groupId')).text = group_id
        ET.SubElement(dependency, qualified('artifactId')).text = artifact_id
        ET.SubElement(dependency, qualified('scope')).text = 'test'

    tree.write(pom_path, xml_declaration=True, encoding='UTF-8')
    print(f'Injected {len(NEW_DEPENDENCIES)} Testcontainers dependencies into {pom_path}')


if __name__ == '__main__':
    main()
