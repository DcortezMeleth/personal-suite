-- "PE unit" was too narrow a name for the mechanism.
--
-- Teaching a group drawn from several classes at once is not specific to
-- sport: the real arkusz routes 19 WF, 3 Etyka and 1 Religia through exactly
-- this shape. The model never cared which subject it was — a unit already
-- names its own — so only the naming was wrong, and it is cheaper to fix now
-- than after the importer is written against it.
ALTER TABLE pe_unit        RENAME TO cross_class_unit;
ALTER TABLE pe_group       RENAME TO cross_class_group;
ALTER TABLE pe_group_class RENAME TO cross_class_group_class;

ALTER TABLE cross_class_unit  RENAME CONSTRAINT pe_unit_blocks_present  TO cross_class_unit_blocks_present;
ALTER TABLE cross_class_unit  RENAME CONSTRAINT pe_unit_blocks_positive TO cross_class_unit_blocks_positive;

ALTER INDEX pe_group_unit RENAME TO cross_class_group_unit;
