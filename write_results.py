#!/usr/bin/env python3

import os
from pprint import pprint
import json
import pandas as pd

from RTE_ChallengeROADEF2020_checker import check_and_display

"""

This script is intended to be used on output from the Java model and assumes
that the timestamped directories produced by the Java model have a certain
structure.

Given directories of data created by the Java model, we need to use the
official python checker to see whether constraints hold, to calculate both
components of the objective, aggregate metadata to easer R processing, and
write this metadata to a json file.

"""

if __name__ == "__main__":

    a13_dir_sweep = {
        "problem": "A_set/A_13.json",
        "solution": "roadef/2020_08_08_16_04_30/A_13/",
        "instance": "A_13"
    }

    a13_dir = {
        "problem": "A_set/A_13.json",
        "solution": "roadef/2020_08_09_12_44_33/A_13/",
        "instance": "A_13"
    }

    a11_dir_0 = {
        "problem": "A_set/A_11.json",
        "solution": "roadef/2020_08_09_21_47_13/A_11/",
        "instance": "A_11"
    }

    a11_dir = {
        "problem": "A_set/A_11.json",
        "solution": "roadef/2020_08_09_18_06_48/A_11/",
        "instance": "A_11"
    }

    a10_dir_0 = {
        "problem": "A_set/A_10.json",
        "solution": "roadef/2020_08_09_21_52_07/A_10/",
        "instance": "A_10"
    }

    a10_dir_fine = {
        "problem": "A_set/A_10.json",
        "solution": "roadef/2020_08_09_17_37_36/A_10/",
        "instance": "A_10"
    }

    a10_dir = {
        "problem": "A_set/A_10.json",
        "solution": "roadef/2020_08_08_16_01_20/A_10/",
        "instance": "A_10"
    }

    a12_dir = {
        "problem": "A_set/A_12.json",
        "solution": "roadef/2020_08_09_17_37_19/A_12/",
        "instance": "A_12"
    }

    a7_dir = {
        "problem": "A_set/A_07.json",
        "solution": "roadef/2020_08_09_17_37_09/A_07/",
        "instance": "A_07"
    }

    a9_dir = {
        "problem": "A_set/A_09.json",
        "solution": "roadef/2020_08_09_17_37_02/A_09/",
        "instance": "A_09"
    }

    a14_dir = {
        "problem": "A_set/A_14.json",
        "solution": "roadef/2020_08_09_05_14_48/A_14/",
        "instance": "A_14"
    }

    a5_dir = {
        "problem": "A_set/A_05.json",
        "solution": "roadef/2020_08_08_02_41_10/A_05/",
        "instance": "A_05"
    }

    a2_dir = {
        "problem": "A_set/A_02.json",
        "solution": "roadef/2020_08_08_00_53_33/A_02/",
        "instance": "A_02"
    }

    a8_dir = {
        "problem": "A_set/A_08.json",
        "solution": "roadef/2020_08_08_00_45_33/A_08/",
        "instance": "A_08"
    }

    ##################################
    #  Fine-grained Parameter Sweep  #
    ##################################

    # Ended up needing to zoom in on parts of the parameter space a nd ran
    # another run of three instances

    a8_dir_fine = {
        "problem": "A_set/A_08.json",
        "solution": "roadef/2020_08_09_01_06_49/A_08/",
        "instance": "A_08"
    }

    a2_dir_fine = {
        "problem": "A_set/A_02.json",
        "solution": "roadef/2020_08_08_17_24_11/A_02/",
        "instance": "A_02"
    }

    a5_dir_fine = {
        "problem": "A_set/A_05.json",
        "solution": "roadef/2020_08_08_20_28_53/A_05/",
        "instance": "A_05"
    }

    a5_dir_sweep3 = {
        "problem": "A_set/A_05.json",
        "solution": "roadef/2020_08_09_03_28_16/A_05/",
        "instance": "A_05"
    }

    a8_dir_sweep3 = {
        "problem": "A_set/A_08.json",
        "solution": "roadef/2020_08_09_03_28_05/A_08/",
        "instance": "A_08"
    }

    problem_maps = [
        a5_dir, a2_dir, a8_dir, a8_dir_fine, a2_dir_fine, a5_dir_fine,
        a5_dir_sweep3, a8_dir_sweep3, a14_dir, a12_dir, a7_dir, a9_dir,
        a10_dir, a10_dir_fine, a11_dir, a13_dir, a13_dir_sweep, a10_dir_0,
        a11_dir_0
    ]

    for problem_map in problem_maps:

        problem_filepath = problem_map["problem"]
        instance = problem_map["instance"]

        ########################
        #  Find Solution Data  #
        ########################

        dir = problem_map["solution"]

        robust_dir = dir + "robust/"

        budget_meta = [{
            "solution_dir": robust_dir + budget + "/",
            "budget": int(budget),
            "type": "robust",
            "instance": instance,
            "problem_filepath": problem_filepath
        } for budget in os.listdir(robust_dir) if not budget.startswith('.')]

        deterministic_meta = {
            "solution_dir": dir + "deterministic/",
            "type": "deterministic",
            "instance": instance,
            "problem_filepath": problem_filepath,
            "budget": None
        }

        model_dirs = [deterministic_meta]
        model_dirs += budget_meta

        for metadata in model_dirs:
            print("Current problem:")
            pprint(metadata)

            problem_filepath = metadata["problem_filepath"]
            solution_dir = metadata["solution_dir"]
            metadata_path = solution_dir + "/" + "metadata.json"

            gurobi_json_filepath = solution_dir + "/" + "model.json"
            stats_json_filepath = solution_dir + "/" + "stats.json"

            with open(gurobi_json_filepath) as f:
                gurobi_metadata = json.load(f)

            with open(stats_json_filepath) as f:
                stats_metadata = json.load(f)

            resource_filepath = solution_dir+"/"+"resources.csv"

            # Write scoring information
            solution_path = solution_dir + "/" + "official_solution.txt"
            official_results, spare_resources = check_and_display(
                problem_filepath, solution_path)


            metadata = {
                **metadata,
                **stats_metadata,
                **gurobi_metadata["SolutionInfo"],
                **official_results
            }
            # Remove this because it creates work in R processing and we don't
            # care about it
            metadata.pop("PoolObjVal", None)
            metadata['IterCount'] = float(metadata['IterCount'])
            metadata['MIPGap'] = float(metadata['MIPGap'])
            metadata['NodeCount'] = float(metadata['NodeCount'])

            mean_risk = metadata.pop("mean_risk", None)
            quantile = metadata.pop("quantile", None)
            excess = metadata.pop("excess", None)

            obj_path = solution_dir + "/" + "obj.csv"
            obj = pd.DataFrame({
                "mean_risk": mean_risk,
                "quantile": quantile,
                "excess": excess
            })
            obj.to_csv(obj_path, index=False)

            pprint(metadata)

            #################################
            #  Update Resource Consumption  #
            #################################

            spare_resources = spare_resources.assign(solution_dir=solution_dir)
            spare_resources = spare_resources.assign(
                instance=metadata['instance'])
            spare_resources = spare_resources.assign(
                type=metadata['type'])
            spare_resources = spare_resources.assign(
                budget=metadata['budget'])
            spare_resources.to_csv(resource_filepath, index=False)

            # Save data
            with open(metadata_path, 'w') as outfile:
                json.dump(metadata, outfile)
