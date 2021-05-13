cd ./benchmarks
for x in "avrora" "luindex" "sunflow"
do
	cd $x
	for y in 1 2
	do 
		./TEST -tool=FT2SS -racepair=../../data_input/input_${x} -maxsleeps=100 -maxTimeOut=50 -maxContSleeps=10 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench | grep "#---#" >> ../../perturb/norep_results_${x}_S100_T50_C10
		./TEST -tool=FT2SS -racepair=../../data_input/input_${x} -maxsleeps=100 -maxTimeOut=50 -maxContSleeps=20 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench | grep "#---#" >> ../../perturb/norep_results_${x}_S100_T50_C20
		./TEST -tool=FT2SS -racepair=../../data_input/input_${x} -maxsleeps=100 -maxTimeOut=100 -maxContSleeps=10 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench | grep "#---#" >> ../../perturb/norep_results_${x}_S100_T100_C10
		./TEST -tool=FT2SS -racepair=../../data_input/input_${x} -maxsleeps=100 -maxTimeOut=100 -maxContSleeps=20 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench | grep "#---#" >> ../../perturb/norep_results_${x}_S100_T100_C20
		./TEST -tool=FT2SS -racepair=../../data_input/input_${x} -maxsleeps=100 -maxTimeOut=200 -maxContSleeps=10 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench | grep "#---#" >> ../../perturb/norep_results_${x}_S100_T200_C10
		./TEST -tool=FT2SS -racepair=../../data_input/input_${x} -maxsleeps=100 -maxTimeOut=200 -maxContSleeps=20 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench | grep "#---#" >> ../../perturb/norep_results_${x}_S100_T200_C20
	done
	cd ../	
done

for x in "xalan" 
do
	cd $x
	for y in 1 2
	do 
		./TEST -tool=FT2SS -racepair=../../data_input/input_${x} -maxsleeps=100 -maxTimeOut=50 -maxContSleeps=10 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench 4| grep "#---#" >> ../../perturb/norep_results_${x}_S100_T50_C10
		./TEST -tool=FT2SS -racepair=../../data_input/input_${x} -maxsleeps=100 -maxTimeOut=50 -maxContSleeps=20 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench 4| grep "#---#" >> ../../perturb/norep_results_${x}_S100_T50_C20
		./TEST -tool=FT2SS -racepair=../../data_input/input_${x} -maxsleeps=100 -maxTimeOut=100 -maxContSleeps=10 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench 4| grep "#---#" >> ../../perturb/norep_results_${x}_S100_T100_C10
		./TEST -tool=FT2SS -racepair=../../data_input/input_${x} -maxsleeps=100 -maxTimeOut=100 -maxContSleeps=20 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench 4| grep "#---#" >> ../../perturb/norep_results_${x}_S100_T100_C20
		./TEST -tool=FT2SS -racepair=../../data_input/input_${x} -maxsleeps=100 -maxTimeOut=200 -maxContSleeps=10 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench 4| grep "#---#" >> ../../perturb/norep_results_${x}_S100_T200_C10
		./TEST -tool=FT2SS -racepair=../../data_input/input_${x} -maxsleeps=100 -maxTimeOut=200 -maxContSleeps=20 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench 4| grep "#---#" >> ../../perturb/norep_results_${x}_S100_T200_C20
	done
	cd ../	
done